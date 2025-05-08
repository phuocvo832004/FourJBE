import json
import logging
import os
import pickle
import tempfile
from datetime import datetime, timezone
from pathlib import Path
import uuid
import base64
import time

import numpy as np
import pandas as pd
from azure.storage.blob import BlobServiceClient, ContainerClient
from azure.cosmos import CosmosClient, PartitionKey
from implicit.als import AlternatingLeastSquares
from scipy.sparse import csr_matrix, load_npz, save_npz
from sklearn.preprocessing import LabelEncoder

# --- Cấu hình logging ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# --- Cấu hình Azure Blob Storage ---
AZURE_CONNECTION_STRING = os.getenv('AZURE_CONNECTION_STRING', '')
AZURE_CONTAINER = os.getenv('AZURE_CONTAINER', 'recommendation-data')
USE_AZURE_STORAGE = os.getenv('USE_AZURE_STORAGE', 'true').lower() == 'true'
AZURE_USER_ENCODER_BLOB_NAME = os.getenv('AZURE_USER_ENCODER_BLOB_NAME', 'user_encoder.pkl')
AZURE_PRODUCT_ENCODER_BLOB_NAME = os.getenv('AZURE_PRODUCT_ENCODER_BLOB_NAME', 'product_encoder.pkl')
AZURE_INTERACTION_MATRIX_BLOB_NAME = os.getenv('AZURE_INTERACTION_MATRIX_BLOB_NAME', 'interaction_matrix.npz')

# --- Cấu hình Azure Cosmos DB ---
COSMOS_ENDPOINT = os.getenv('COSMOS_ENDPOINT')
COSMOS_KEY = os.getenv('COSMOS_KEY')
COSMOS_DATABASE_NAME = os.getenv('COSMOS_DATABASE_NAME', 'RecommendationDB')
COSMOS_CONTAINER_NAME = os.getenv('COSMOS_MODELS_CONTAINER_NAME', 'Models') # Container riêng cho models

# --- Đường dẫn dữ liệu mới ---
NEW_DATA_PATH = os.getenv('NEW_DATA_PATH', 'processed-interactions/new/')
ARCHIVE_PATH = os.getenv('ARCHIVE_PATH', 'processed-interactions/archived/')

# --- Đường dẫn artifacts ---
ARTIFACTS_PATH = os.getenv('ARTIFACTS_PATH', 'artifacts/')

# --- Cấu hình model ---
FACTORS = int(os.getenv('ALS_FACTORS', '100'))  # Số lượng yếu tố ẩn
REGULARIZATION = float(os.getenv('ALS_REGULARIZATION', '0.01'))  # Hệ số regularization
ITERATIONS = int(os.getenv('ALS_ITERATIONS', '15'))  # Số lần lặp
ALPHA = float(os.getenv('ALS_ALPHA', '1.0'))  # Alpha parameter (confidence scaling)

def download_blob_to_file(blob_client, file_path, max_retries=3, retry_delay=2):
    """
    Tải một blob từ Azure Blob Storage về tệp cục bộ với cơ chế thử lại
    
    Args:
        blob_client: Azure blob client
        file_path (str): Đường dẫn tệp cục bộ để lưu
        max_retries (int): Số lần thử lại tối đa
        retry_delay (int): Thời gian chờ giữa các lần thử lại (giây)
    
    Returns:
        bool: True nếu thành công, False nếu không
    """
    for attempt in range(max_retries + 1):
        try:
            with open(file_path, "wb") as file:
                data = blob_client.download_blob()
                file.write(data.readall())
            return True
        except Exception as e:
            if "503" in str(e) or "timeout" in str(e).lower() or "connection" in str(e).lower():
                # Lỗi kết nối tạm thời
                if attempt < max_retries:
                    wait_time = retry_delay * (2 ** attempt)  # Exponential backoff
                    logger.warning(f"Lỗi kết nối tạm thời khi tải blob, thử lại sau {wait_time}s: {e}")
                    time.sleep(wait_time)
                    continue
            logger.error(f"Lỗi khi tải blob (lần {attempt+1}/{max_retries+1}): {e}")
            return False
    return False

def upload_file_to_blob(container_client, file_path, blob_name, max_retries=3, retry_delay=2):
    """
    Tải một tệp lên Azure Blob Storage với cơ chế thử lại
    
    Args:
        container_client: Azure container client
        file_path (str): Đường dẫn tệp cục bộ để tải lên
        blob_name (str): Tên của blob
        max_retries (int): Số lần thử lại tối đa
        retry_delay (int): Thời gian chờ giữa các lần thử lại (giây)
        
    Returns:
        bool: True nếu thành công, False nếu không
    """
    for attempt in range(max_retries + 1):
        try:
            blob_client = container_client.get_blob_client(blob_name)
            with open(file_path, "rb") as file:
                blob_client.upload_blob(file, overwrite=True)
            return True
        except Exception as e:
            if "503" in str(e) or "timeout" in str(e).lower() or "connection" in str(e).lower():
                # Lỗi kết nối tạm thời
                if attempt < max_retries:
                    wait_time = retry_delay * (2 ** attempt)  # Exponential backoff
                    logger.warning(f"Lỗi kết nối tạm thời khi tải lên blob, thử lại sau {wait_time}s: {e}")
                    time.sleep(wait_time)
                    continue
            logger.error(f"Lỗi khi tải lên blob (lần {attempt+1}/{max_retries+1}): {e}")
            return False
    return False

def load_interaction_files(container_client, new_data_path, batch_size=10, validate_data=True):
    """
    Tải tất cả các files tương tác mới từ Azure Blob Storage
    
    Args:
        container_client: Azure container client
        new_data_path (str): Đường dẫn chứa các files tương tác mới
        batch_size (int): Số lượng files xử lý trong một lô
        validate_data (bool): Có kiểm tra tính hợp lệ của dữ liệu không
        
    Returns:
        pandas.DataFrame: DataFrame chứa các tương tác đã được gộp
    """
    all_interactions = []
    
    # Liệt kê tất cả các blobs trong đường dẫn
    blob_list = list(container_client.list_blobs(name_starts_with=new_data_path))
    blob_count = 0
    processed_count = 0
    invalid_count = 0
    
    # Xử lý theo lô
    for i in range(0, len(blob_list), batch_size):
        batch_blobs = blob_list[i:i+batch_size]
        batch_interactions = []
        
        logger.info(f"Xử lý lô {i//batch_size + 1}/{(len(blob_list) + batch_size - 1)//batch_size} ({len(batch_blobs)} files)")
        
        for blob in batch_blobs:
            try:
                # Tải nội dung của blob
                blob_client = container_client.get_blob_client(blob.name)
                data = blob_client.download_blob().readall()
                
                # Parse JSON
                interactions = json.loads(data)
                
                if validate_data:
                    # Kiểm tra tính hợp lệ của dữ liệu
                    valid_interactions = []
                    for interaction in interactions:
                        # Kiểm tra các trường bắt buộc
                        if not all(k in interaction for k in ['user_id', 'product_id', 'quantity']):
                            logger.warning(f"Bỏ qua tương tác không hợp lệ, thiếu trường: {interaction}")
                            invalid_count += 1
                            continue
                        
                        # Kiểm tra kiểu dữ liệu
                        try:
                            user_id = int(interaction['user_id'])
                            product_id = int(interaction['product_id'])
                            quantity = float(interaction['quantity'])
                            
                            # Kiểm tra giá trị hợp lệ
                            if user_id <= 0 or product_id <= 0 or quantity <= 0:
                                logger.warning(f"Bỏ qua tương tác với giá trị không hợp lệ: {interaction}")
                                invalid_count += 1
                                continue
                                
                            # Cập nhật với giá trị đã chuyển đổi
                            interaction['user_id'] = user_id
                            interaction['product_id'] = product_id
                            interaction['quantity'] = quantity
                            
                            valid_interactions.append(interaction)
                        except (ValueError, TypeError):
                            logger.warning(f"Bỏ qua tương tác với kiểu dữ liệu không hợp lệ: {interaction}")
                            invalid_count += 1
                            continue
                    
                    batch_interactions.extend(valid_interactions)
                else:
                    # Nếu không kiểm tra tính hợp lệ, thêm tất cả
                    batch_interactions.extend(interactions)
                
                blob_count += 1
                processed_count += len(batch_interactions)
                
            except json.JSONDecodeError:
                logger.error(f"Lỗi khi phân tích JSON từ blob {blob.name}")
            except Exception as e:
                logger.error(f"Lỗi khi xử lý blob {blob.name}: {e}")
        
        # Thêm tương tác từ lô hiện tại vào danh sách tổng
        all_interactions.extend(batch_interactions)
        logger.info(f"Lô {i//batch_size + 1} đã xử lý: {len(batch_interactions)} tương tác hợp lệ")
    
    logger.info(f"Đã đọc {blob_count} files, tổng số {processed_count} tương tác hợp lệ")
    if invalid_count > 0:
        logger.warning(f"Đã bỏ qua {invalid_count} tương tác không hợp lệ")
    
    # Nếu không có tương tác nào, trả về DataFrame trống
    if not all_interactions:
        return pd.DataFrame()
    
    # Chuyển đổi thành DataFrame
    return pd.DataFrame(all_interactions)

def archive_processed_files(container_client, new_data_path, archive_path):
    """
    Di chuyển các files đã xử lý sang thư mục lưu trữ
    
    Args:
        container_client: Azure container client
        new_data_path (str): Đường dẫn chứa các files tương tác mới
        archive_path (str): Đường dẫn thư mục lưu trữ
        
    Returns:
        int: Số lượng files đã được lưu trữ
    """
    # Tạo đường dẫn lưu trữ với timestamp
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    target_path = f"{archive_path}{timestamp}/"
    
    # Liệt kê tất cả các blobs trong đường dẫn
    blob_list = container_client.list_blobs(name_starts_with=new_data_path)
    archived_count = 0
    
    for blob in blob_list:
        try:
            # Tên blob mới
            old_name = blob.name
            file_name = old_name.split('/')[-1]
            new_name = f"{target_path}{file_name}"
            
            # Tải nội dung
            source_blob = container_client.get_blob_client(old_name)
            data = source_blob.download_blob().readall()
            
            # Tạo blob mới
            target_blob = container_client.get_blob_client(new_name)
            target_blob.upload_blob(data)
            
            # Xóa blob cũ
            source_blob.delete_blob()
            
            archived_count += 1
        except Exception as e:
            logger.error(f"Lỗi khi lưu trữ blob {blob.name}: {e}")
    
    logger.info(f"Đã lưu trữ {archived_count} files vào {target_path}")
    return archived_count

def initialize_cosmos_db():
    """Khởi tạo Cosmos DB client và tạo database/container nếu chưa có."""
    if not COSMOS_ENDPOINT or not COSMOS_KEY:
        logger.error("COSMOS_ENDPOINT hoặc COSMOS_KEY chưa được cấu hình.")
        return None
    try:
        client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
        database = client.create_database_if_not_exists(id=COSMOS_DATABASE_NAME)
        container = database.create_container_if_not_exists(
            id=COSMOS_CONTAINER_NAME,
            partition_key=PartitionKey(path="/modelType"), # Chọn Partition Key hợp lý, ví dụ: loại model
            offer_throughput=400
        )
        logger.info(f"Cosmos DB database '{COSMOS_DATABASE_NAME}' và container '{COSMOS_CONTAINER_NAME}' đã sẵn sàng.")
        return container
    except Exception as e:
        logger.error(f"Lỗi khi khởi tạo Cosmos DB: {e}")
        return None

def load_latest_model_from_cosmos(cosmos_container):
    """
    Tải model và metadata mới nhất từ Cosmos DB.
    
    Args:
        cosmos_container: Cosmos DB container client
        
    Returns:
        tuple: (model_doc, model) hoặc (None, None) nếu không tìm thấy
    """
    if not cosmos_container:
        return None, None
    try:
        # Truy vấn document mô tả model mới nhất
        query = "SELECT TOP 1 * FROM c WHERE c.documentType = 'modelDescriptor' ORDER BY c.timestamp DESC"
        items = list(cosmos_container.query_items(
            query=query,
            enable_cross_partition_query=True
        ))
        
        if not items:
            # Thử truy vấn cũ nếu không tìm thấy document mô tả
            query = "SELECT TOP 1 * FROM c ORDER BY c.timestamp DESC"
            items = list(cosmos_container.query_items(
                query=query,
                enable_cross_partition_query=True
            ))
        
        if items:
            model_doc = items[0]
            logger.info(f"Tìm thấy model trong Cosmos DB, ID: {model_doc.get('id')}, Timestamp: {model_doc.get('timestamp')}")
            
            # Kiểm tra xem document là descriptor hay chứa model trực tiếp
            if 'modelChunkPartitionKey' in model_doc:
                # Đây là document mô tả, cần tải các chunks
                model_chunk_partition_key = model_doc.get('modelChunkPartitionKey', "alsmodel")
                logger.info(f"Tải model từ chunks với partition key: '{model_chunk_partition_key}'")
                
                # Truy vấn tất cả các chunks của model này, sắp xếp theo chunk_index
                chunk_query = "SELECT * FROM c WHERE c.partition_key = @pk ORDER BY c.chunk_index ASC"
                chunk_query_params = [dict(name="@pk", value=model_chunk_partition_key)]
                
                model_chunk_docs = list(cosmos_container.query_items(
                    query=chunk_query,
                    parameters=chunk_query_params,
                    enable_cross_partition_query=True
                ))
                
                if not model_chunk_docs:
                    logger.error(f"Không tìm thấy chunks model cho partition key '{model_chunk_partition_key}'")
                    return model_doc, None
                
                # Ghép các chunks lại
                try:
                    base64_encoded_model_str = "".join([chunk_doc['file_chunk'] for chunk_doc in model_chunk_docs])
                    model_bytes = base64.b64decode(base64_encoded_model_str)
                    model = pickle.loads(model_bytes)
                    logger.info(f"Đã tải model thành công từ {len(model_chunk_docs)} chunks")
                    return model_doc, model
                except Exception as e:
                    logger.error(f"Lỗi khi giải mã model từ chunks: {e}")
                    return model_doc, None
            
            elif 'modelData' in model_doc:
                # Document có chứa dữ liệu model trực tiếp
                if isinstance(model_doc['modelData'], bytes):
                    model = pickle.loads(model_doc['modelData'])
                elif isinstance(model_doc['modelData'], str):
                    # Nếu lưu dạng base64 encoded string
                    model_bytes = base64.b64decode(model_doc['modelData'])
                    model = pickle.loads(model_bytes)
                else:
                    logger.warning("Định dạng 'modelData' không đúng trong document model.")
                    return model_doc, None
                
                logger.info("Đã tải model trực tiếp từ document")
                return model_doc, model
            else:
                logger.warning("Không tìm thấy dữ liệu model trong document")
                return model_doc, None
        else:
            logger.info("Không tìm thấy model nào trong Cosmos DB.")
            return None, None
    except Exception as e:
        logger.error(f"Lỗi khi tải model từ Cosmos DB: {e}")
        return None, None

def load_existing_artifacts(blob_service_client, container_name, cosmos_container, temp_dir):
    """
    Tải các artifacts hiện có: model từ Cosmos DB, còn lại từ Blob Storage
    """
    container_client = blob_service_client.get_container_client(container_name)
    artifacts = {}

    # 1. Tải model và metadata từ Cosmos DB
    model_doc, model = load_latest_model_from_cosmos(cosmos_container)
    
    if model:
        artifacts['model'] = model
        logger.info("Đã tải model từ Cosmos DB")
    else:
        # Tạo mô hình mới nếu không tìm thấy trong Cosmos DB
        artifacts['model'] = AlternatingLeastSquares(
            factors=FACTORS,
            regularization=REGULARIZATION,
            iterations=ITERATIONS,
            alpha=ALPHA,
            random_state=42
        )
        logger.info("Không tìm thấy model trong Cosmos DB, tạo model mới")

    # Xác định tên blob cho các artifacts từ model_doc hoặc sử dụng mặc định
    user_encoder_blob_name = AZURE_USER_ENCODER_BLOB_NAME
    product_encoder_blob_name = AZURE_PRODUCT_ENCODER_BLOB_NAME
    interaction_matrix_blob_name = AZURE_INTERACTION_MATRIX_BLOB_NAME
    
    if model_doc:
        user_encoder_blob_name = model_doc.get('userEncoderBlob', AZURE_USER_ENCODER_BLOB_NAME)
        product_encoder_blob_name = model_doc.get('productEncoderBlob', AZURE_PRODUCT_ENCODER_BLOB_NAME)
        interaction_matrix_blob_name = model_doc.get('interactionMatrixBlob', AZURE_INTERACTION_MATRIX_BLOB_NAME)
        logger.info(f"Sử dụng tên blob từ model descriptor:")
        logger.info(f"- User encoder: {user_encoder_blob_name}")
        logger.info(f"- Product encoder: {product_encoder_blob_name}")
        logger.info(f"- Interaction matrix: {interaction_matrix_blob_name}")

    # Đường dẫn tệp cục bộ cho các artifacts khác
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    # 2. Kiểm tra và tải user encoder từ Blob
    user_encoder_blob = container_client.get_blob_client(user_encoder_blob_name)
    if user_encoder_blob.exists():
        download_blob_to_file(user_encoder_blob, user_encoder_path)
        with open(user_encoder_path, 'rb') as f:
            artifacts['user_encoder'] = pickle.load(f)
        logger.info(f"Đã tải user encoder hiện có ({len(artifacts['user_encoder'].classes_)} users) từ Blob")
    else:
        artifacts['user_encoder'] = LabelEncoder()
        logger.info(f"Không tìm thấy user encoder trong Blob tại {user_encoder_blob_name}, tạo mới")

    # 3. Kiểm tra và tải product encoder từ Blob
    product_encoder_blob = container_client.get_blob_client(product_encoder_blob_name)
    if product_encoder_blob.exists():
        download_blob_to_file(product_encoder_blob, product_encoder_path)
        with open(product_encoder_path, 'rb') as f:
            artifacts['product_encoder'] = pickle.load(f)
        logger.info(f"Đã tải product encoder hiện có ({len(artifacts['product_encoder'].classes_)} products) từ Blob")
    else:
        artifacts['product_encoder'] = LabelEncoder()
        logger.info(f"Không tìm thấy product encoder trong Blob tại {product_encoder_blob_name}, tạo mới")

    # 4. Kiểm tra và tải interaction matrix từ Blob
    interaction_matrix_blob = container_client.get_blob_client(interaction_matrix_blob_name)
    if interaction_matrix_blob.exists():
        download_blob_to_file(interaction_matrix_blob, interaction_matrix_path)
        artifacts['interaction_matrix'] = load_npz(interaction_matrix_path)
        logger.info(f"Đã tải interaction matrix hiện có từ Blob, shape: {artifacts['interaction_matrix'].shape}")
    else:
        logger.info(f"Không tìm thấy interaction matrix trong Blob tại {interaction_matrix_blob_name}, sẽ tạo từ dữ liệu mới")

    return artifacts

def update_artifacts_with_new_data(artifacts, interactions_df):
    """
    Cập nhật artifacts hiện có với dữ liệu tương tác mới
    
    Args:
        artifacts (dict): Dictionary chứa các artifacts hiện có
        interactions_df (pandas.DataFrame): DataFrame chứa dữ liệu tương tác mới
        
    Returns:
        dict: Dictionary chứa các artifacts đã cập nhật
    """
    if interactions_df.empty:
        logger.info("Không có dữ liệu mới để cập nhật")
        return artifacts
    
    user_encoder = artifacts['user_encoder']
    product_encoder = artifacts['product_encoder']
    
    # Xử lý dữ liệu tương tác
    # Chỉ lấy các cột cần thiết
    df = interactions_df[['user_id', 'product_id', 'quantity']].copy()
    
    # Chuyển đổi từ string sang int nếu cần
    if df['user_id'].dtype == 'object':
        df['user_id'] = df['user_id'].astype(int)
    if df['product_id'].dtype == 'object':
        df['product_id'] = df['product_id'].astype(int)
    
    # Update encoders với dữ liệu mới
    # Lưu ý: Nếu đã có dữ liệu, cần kết hợp dữ liệu cũ và mới
    unique_users = df['user_id'].unique()
    unique_products = df['product_id'].unique()
    
    # Nếu encoder đã có dữ liệu trước đó
    if hasattr(user_encoder, 'classes_') and len(user_encoder.classes_) > 0:
        # Kết hợp các giá trị cũ và mới
        all_users = np.unique(np.concatenate([user_encoder.classes_, unique_users]))
        user_encoder.classes_ = all_users
    else:
        # Fit encoder với dữ liệu mới
        user_encoder.fit(unique_users)
    
    if hasattr(product_encoder, 'classes_') and len(product_encoder.classes_) > 0:
        # Kết hợp các giá trị cũ và mới
        all_products = np.unique(np.concatenate([product_encoder.classes_, unique_products]))
        product_encoder.classes_ = all_products
    else:
        # Fit encoder với dữ liệu mới
        product_encoder.fit(unique_products)
    
    # Chuyển đổi user_id và product_id thành indices
    df['user_idx'] = user_encoder.transform(df['user_id'])
    df['product_idx'] = product_encoder.transform(df['product_id'])
    
    # Tạo ma trận tương tác mới từ dữ liệu mới
    user_count = len(user_encoder.classes_)
    product_count = len(product_encoder.classes_)
    
    # Tạo sparse matrix từ dữ liệu mới
    rows = df['user_idx'].values
    cols = df['product_idx'].values
    data = df['quantity'].values
    
    new_matrix = csr_matrix((data, (rows, cols)), shape=(user_count, product_count))
    
    # Nếu đã có ma trận cũ, kết hợp với ma trận mới
    if 'interaction_matrix' in artifacts:
        old_matrix = artifacts['interaction_matrix']
        
        # Nếu kích thước ma trận cũ khác với ma trận mới, cần resize
        if old_matrix.shape != new_matrix.shape:
            # Tạo ma trận lớn hơn
            old_data = old_matrix.data
            old_indices = old_matrix.indices
            old_indptr = old_matrix.indptr
            
            # Tạo ma trận mới với kích thước phù hợp
            resized_matrix = csr_matrix((old_data, old_indices, old_indptr), 
                                         shape=(user_count, product_count))
            
            # Cộng ma trận cũ và mới
            combined_matrix = resized_matrix + new_matrix
        else:
            # Cộng trực tiếp nếu kích thước giống nhau
            combined_matrix = old_matrix + new_matrix
        
        artifacts['interaction_matrix'] = combined_matrix
        logger.info(f"Đã kết hợp ma trận cũ và mới, kích thước mới: {combined_matrix.shape}")
    else:
        # Nếu chưa có ma trận cũ
        artifacts['interaction_matrix'] = new_matrix
        logger.info(f"Đã tạo ma trận tương tác mới, kích thước: {new_matrix.shape}")
    
    # Huấn luyện lại mô hình
    model = artifacts['model']
    model.fit(artifacts['interaction_matrix'])
    artifacts['model'] = model
    logger.info("Đã huấn luyện lại mô hình ALS")
    
    return artifacts

def save_model_to_cosmosdb(model, cosmos_container, user_encoder_blob_name, product_encoder_blob_name, matrix_blob_name):
    """Lưu model và metadata vào Cosmos DB."""
    if not cosmos_container:
        logger.error("Cosmos DB container chưa được khởi tạo.")
        return False
    try:
        # Serialize model thành bytes
        model_bytes = pickle.dumps(model)
        model_base64 = base64.b64encode(model_bytes).decode('utf-8')
        
        # Phân chia mô hình nếu cần (vì giới hạn kích thước document của Cosmos DB)
        # Kích thước tối đa cho mỗi document Cosmos DB là 2MB
        max_chunk_size = 900000  # ~900KB để dư ra cho metadata
        model_chunks = []
        
        # Chia mô hình thành các chunks
        for i in range(0, len(model_base64), max_chunk_size):
            chunk = model_base64[i:i+max_chunk_size]
            model_chunks.append(chunk)
        
        logger.info(f"Phân chia model thành {len(model_chunks)} chunks để lưu vào Cosmos DB")
        
        # Tạo partition_key cho các chunks
        partition_key_value = "alsmodel"
        
        # Lưu từng chunk vào Cosmos DB
        for i, chunk in enumerate(model_chunks):
            chunk_doc = {
                'id': str(uuid.uuid4()),
                'chunk_index': i,
                'total_chunks': len(model_chunks),
                'partition_key': partition_key_value,
                'file_chunk': chunk
            }
            cosmos_container.create_item(body=chunk_doc)
        
        # Tạo document mô tả model
        model_descriptor = {
            'id': str(uuid.uuid4()),
            'documentType': 'modelDescriptor',
            'modelType': 'ALS',
            'modelChunkPartitionKey': partition_key_value,
            'timestamp': datetime.now(timezone.utc).isoformat(),
            'userEncoderBlob': user_encoder_blob_name,
            'productEncoderBlob': product_encoder_blob_name,
            'interactionMatrixBlob': matrix_blob_name,
            'factors': FACTORS,
            'regularization': REGULARIZATION,
            'iterations': ITERATIONS,
            'alpha': ALPHA
        }
        
        # Lưu document mô tả vào Cosmos DB
        cosmos_container.create_item(body=model_descriptor)
        logger.info(f"Đã lưu model descriptor vào Cosmos DB, ID: {model_descriptor['id']}")
        return True
    except Exception as e:
        logger.error(f"Lỗi khi lưu model vào Cosmos DB: {e}")
        return False

def save_artifacts(artifacts, blob_service_client, container_name, cosmos_container, temp_dir):
    """
    Lưu artifacts: model vào Cosmos DB, còn lại vào Azure Blob Storage
    """
    container_client = blob_service_client.get_container_client(container_name)

    # Đường dẫn tệp cục bộ
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    blob_success = True
    cosmos_success = False

    try:
        # 1. Lưu user encoder vào Blob
        with open(user_encoder_path, 'wb') as f:
            pickle.dump(artifacts['user_encoder'], f)
        if not upload_file_to_blob(container_client, user_encoder_path, AZURE_USER_ENCODER_BLOB_NAME):
            blob_success = False

        # 2. Lưu product encoder vào Blob
        with open(product_encoder_path, 'wb') as f:
            pickle.dump(artifacts['product_encoder'], f)
        if not upload_file_to_blob(container_client, product_encoder_path, AZURE_PRODUCT_ENCODER_BLOB_NAME):
            blob_success = False

        # 3. Lưu interaction matrix vào Blob
        if 'interaction_matrix' in artifacts:
            save_npz(interaction_matrix_path, artifacts['interaction_matrix'])
            if not upload_file_to_blob(container_client, interaction_matrix_path, AZURE_INTERACTION_MATRIX_BLOB_NAME):
                 blob_success = False
        else:
             logger.warning("Không có interaction matrix để lưu.")

        if blob_success:
             logger.info("Đã lưu encoder và matrix vào Azure Blob Storage")
             # 4. Lưu model vào Cosmos DB theo cách mới
             cosmos_success = save_model_to_cosmosdb(
                 artifacts['model'],
                 cosmos_container,
                 AZURE_USER_ENCODER_BLOB_NAME,
                 AZURE_PRODUCT_ENCODER_BLOB_NAME,
                 AZURE_INTERACTION_MATRIX_BLOB_NAME
            )
        else:
             logger.error("Lỗi khi lưu encoder/matrix vào Blob Storage, sẽ không lưu model vào Cosmos DB.")

        return blob_success and cosmos_success

    except Exception as e:
        logger.error(f"Lỗi khi lưu artifacts: {e}")
        return False

def run_batch_update():
    """Chạy job cập nhật batch"""
    start_time = time.time()
    job_metrics = {
        "files_processed": 0,
        "interactions_processed": 0,
        "invalid_interactions": 0,
        "new_users": 0,
        "new_products": 0,
        "archived_files": 0
    }
    
    logger.info("Bắt đầu job cập nhật batch...")

    # Kiểm tra cấu hình Azure
    if not USE_AZURE_STORAGE:
        logger.error("USE_AZURE_STORAGE không được bật, dừng job.")
        return
        
    if not AZURE_CONNECTION_STRING:
        logger.error("AZURE_CONNECTION_STRING không được cấu hình")
        return

    # Khởi tạo Cosmos DB
    cosmos_init_start = time.time()
    cosmos_container = initialize_cosmos_db()
    logger.info(f"Khởi tạo Cosmos DB mất {time.time() - cosmos_init_start:.2f} giây")
    
    if not cosmos_container:
        logger.error("Không thể khởi tạo Cosmos DB, dừng job.")
        return

    try:
        # Khởi tạo Azure Blob Storage client
        blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
        blob_container_client = blob_service_client.get_container_client(AZURE_CONTAINER)

        # Kiểm tra xem có files mới không
        blob_check_start = time.time()
        blob_list = list(blob_container_client.list_blobs(name_starts_with=NEW_DATA_PATH))
        logger.info(f"Kiểm tra files mới mất {time.time() - blob_check_start:.2f} giây, tìm thấy {len(blob_list)} files")
        
        if not blob_list:
            logger.info("Không có files tương tác mới, kết thúc job")
            return

        # Tạo thư mục tạm để lưu artifacts
        with tempfile.TemporaryDirectory() as temp_dir:
            # Tải artifacts hiện có từ Azure (Model từ Cosmos DB, encoders và matrix từ Blob)
            load_artifacts_start = time.time()
            artifacts = load_existing_artifacts(blob_service_client, AZURE_CONTAINER, cosmos_container, temp_dir)
            logger.info(f"Tải artifacts hiện có mất {time.time() - load_artifacts_start:.2f} giây")
            
            # Lưu lại số lượng users và products trước khi cập nhật
            user_count_before = len(artifacts['user_encoder'].classes_) if hasattr(artifacts['user_encoder'], 'classes_') else 0
            product_count_before = len(artifacts['product_encoder'].classes_) if hasattr(artifacts['product_encoder'], 'classes_') else 0
            
            # Tải dữ liệu tương tác mới từ Blob
            load_interactions_start = time.time()
            interactions_df = load_interaction_files(blob_container_client, NEW_DATA_PATH)
            load_interactions_time = time.time() - load_interactions_start
            
            # Cập nhật metrics
            job_metrics["files_processed"] = len(blob_list)
            job_metrics["interactions_processed"] = len(interactions_df) if not interactions_df.empty else 0
            
            if interactions_df.empty:
                logger.warning("Không có dữ liệu tương tác hợp lệ sau khi kiểm tra")
                return
                
            logger.info(f"Tải và xử lý dữ liệu tương tác mới mất {load_interactions_time:.2f} giây, {len(interactions_df)} tương tác")

            # Cập nhật artifacts với dữ liệu mới và huấn luyện lại model
            update_start = time.time()
            updated_artifacts = update_artifacts_with_new_data(artifacts, interactions_df)
            update_time = time.time() - update_start
            
            # Tính số users và products mới được thêm vào
            user_count_after = len(updated_artifacts['user_encoder'].classes_) if hasattr(updated_artifacts['user_encoder'], 'classes_') else 0
            product_count_after = len(updated_artifacts['product_encoder'].classes_) if hasattr(updated_artifacts['product_encoder'], 'classes_') else 0
            
            job_metrics["new_users"] = user_count_after - user_count_before
            job_metrics["new_products"] = product_count_after - product_count_before
            
            logger.info(f"Cập nhật artifacts và huấn luyện lại model mất {update_time:.2f} giây")
            logger.info(f"Đã thêm {job_metrics['new_users']} users mới và {job_metrics['new_products']} products mới")

            # Lưu artifacts đã cập nhật (Model vào Cosmos DB, còn lại vào Blob)
            save_start = time.time()
            success = save_artifacts(
                updated_artifacts,
                blob_service_client,
                AZURE_CONTAINER,
                cosmos_container,
                temp_dir
            )
            logger.info(f"Lưu artifacts đã cập nhật mất {time.time() - save_start:.2f} giây")

            if success:
                # Lưu trữ các files đã xử lý trong Blob
                archive_start = time.time()
                archived_count = archive_processed_files(blob_container_client, NEW_DATA_PATH, ARCHIVE_PATH)
                job_metrics["archived_files"] = archived_count
                logger.info(f"Lưu trữ {archived_count} files đã xử lý mất {time.time() - archive_start:.2f} giây")
                
                total_time = time.time() - start_time
                logger.info(f"Job cập nhật hoàn tất thành công trong {total_time:.2f} giây")
                
                # Log metrics tổng hợp
                logger.info(f"Thống kê job: {job_metrics}")
            else:
                logger.error("Không thể lưu artifacts, hủy job")

    except Exception as e:
        logger.error(f"Lỗi khi chạy job cập nhật batch: {e}")
        import traceback
        logger.error(traceback.format_exc())

if __name__ == "__main__":
    run_batch_update() 