#!/usr/bin/env python3
# app/batch_update.py - Batch job cập nhật artifacts từ dữ liệu mới

import json
import logging
import os
import pickle
import tempfile
from datetime import datetime, timezone
from pathlib import Path
import uuid

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
USER_ENCODER_BLOB_NAME = os.getenv('USER_ENCODER_BLOB_NAME', 'artifacts/user_encoder.pkl')
PRODUCT_ENCODER_BLOB_NAME = os.getenv('PRODUCT_ENCODER_BLOB_NAME', 'artifacts/product_encoder.pkl')
INTERACTION_MATRIX_BLOB_NAME = os.getenv('INTERACTION_MATRIX_BLOB_NAME', 'artifacts/interaction_matrix.npz')

# --- Cấu hình model ---
FACTORS = int(os.getenv('ALS_FACTORS', '100'))  # Số lượng yếu tố ẩn
REGULARIZATION = float(os.getenv('ALS_REGULARIZATION', '0.01'))  # Hệ số regularization
ITERATIONS = int(os.getenv('ALS_ITERATIONS', '15'))  # Số lần lặp
ALPHA = float(os.getenv('ALS_ALPHA', '1.0'))  # Alpha parameter (confidence scaling)

def download_blob_to_file(blob_client, file_path):
    """
    Tải một blob từ Azure Blob Storage về tệp cục bộ
    
    Args:
        blob_client: Azure blob client
        file_path (str): Đường dẫn tệp cục bộ để lưu
    
    Returns:
        bool: True nếu thành công, False nếu không
    """
    try:
        with open(file_path, "wb") as file:
            data = blob_client.download_blob()
            file.write(data.readall())
        return True
    except Exception as e:
        logger.error(f"Lỗi khi tải blob: {e}")
        return False

def upload_file_to_blob(container_client, file_path, blob_name):
    """
    Tải một tệp lên Azure Blob Storage
    
    Args:
        container_client: Azure container client
        file_path (str): Đường dẫn tệp cục bộ để tải lên
        blob_name (str): Tên của blob
        
    Returns:
        bool: True nếu thành công, False nếu không
    """
    try:
        blob_client = container_client.get_blob_client(blob_name)
        with open(file_path, "rb") as file:
            blob_client.upload_blob(file, overwrite=True)
        return True
    except Exception as e:
        logger.error(f"Lỗi khi tải lên blob: {e}")
        return False

def load_interaction_files(container_client, new_data_path):
    """
    Tải tất cả các files tương tác mới từ Azure Blob Storage
    
    Args:
        container_client: Azure container client
        new_data_path (str): Đường dẫn chứa các files tương tác mới
        
    Returns:
        pandas.DataFrame: DataFrame chứa các tương tác đã được gộp
    """
    all_interactions = []
    
    # Liệt kê tất cả các blobs trong đường dẫn
    blob_list = container_client.list_blobs(name_starts_with=new_data_path)
    blob_count = 0
    
    for blob in blob_list:
        try:
            # Tải nội dung của blob
            blob_client = container_client.get_blob_client(blob.name)
            data = blob_client.download_blob().readall()
            
            # Parse JSON
            interactions = json.loads(data)
            if interactions:
                all_interactions.extend(interactions)
            
            blob_count += 1
        except Exception as e:
            logger.error(f"Lỗi khi xử lý blob {blob.name}: {e}")
    
    logger.info(f"Đã đọc {blob_count} files, tổng số {len(all_interactions)} tương tác")
    
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
    """Tải model mới nhất từ Cosmos DB."""
    if not cosmos_container:
        return None
    try:
        # Query model mới nhất dựa trên timestamp (hoặc version)
        # Giả sử lưu timestamp dưới dạng ISO 8601 string
        query = f"SELECT TOP 1 * FROM c ORDER BY c.timestamp DESC"
        items = list(cosmos_container.query_items(
            query=query,
            enable_cross_partition_query=True # Cần thiết nếu partition key không phải là timestamp
        ))
        if items:
            model_doc = items[0]
            logger.info(f"Tìm thấy model trong Cosmos DB, ID: {model_doc.get('id')}, Timestamp: {model_doc.get('timestamp')}")
            # Deserialize model từ bytes (giả sử lưu model dạng bytes trong 'modelData')
            if 'modelData' in model_doc and isinstance(model_doc['modelData'], bytes):
                 model = pickle.loads(model_doc['modelData'])
                 return model
            elif 'modelData' in model_doc and isinstance(model_doc['modelData'], str):
                 # Nếu lưu dạng base64 encoded string
                 import base64
                 model_bytes = base64.b64decode(model_doc['modelData'])
                 model = pickle.loads(model_bytes)
                 return model
            else:
                logger.warning("Không tìm thấy trường 'modelData' hoặc định dạng không đúng trong document model.")
                return None
        else:
            logger.info("Không tìm thấy model nào trong Cosmos DB.")
            return None
    except Exception as e:
        logger.error(f"Lỗi khi tải model từ Cosmos DB: {e}")
        return None

def load_existing_artifacts(blob_service_client, container_name, cosmos_container, temp_dir):
    """
    Tải các artifacts hiện có: model từ Cosmos DB, còn lại từ Blob Storage
    """
    container_client = blob_service_client.get_container_client(container_name)
    artifacts = {}

    # 1. Tải model từ Cosmos DB
    model = load_latest_model_from_cosmos(cosmos_container)
    if model:
        artifacts['model'] = model
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

    # Đường dẫn tệp cục bộ cho các artifacts khác
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    # 2. Kiểm tra và tải user encoder từ Blob
    user_encoder_blob = container_client.get_blob_client(USER_ENCODER_BLOB_NAME)
    if user_encoder_blob.exists():
        download_blob_to_file(user_encoder_blob, user_encoder_path)
        with open(user_encoder_path, 'rb') as f:
            artifacts['user_encoder'] = pickle.load(f)
        logger.info(f"Đã tải user encoder hiện có ({len(artifacts['user_encoder'].classes_)} users) từ Blob")
    else:
        artifacts['user_encoder'] = LabelEncoder()
        logger.info("Không tìm thấy user encoder trong Blob, tạo mới")

    # 3. Kiểm tra và tải product encoder từ Blob
    product_encoder_blob = container_client.get_blob_client(PRODUCT_ENCODER_BLOB_NAME)
    if product_encoder_blob.exists():
        download_blob_to_file(product_encoder_blob, product_encoder_path)
        with open(product_encoder_path, 'rb') as f:
            artifacts['product_encoder'] = pickle.load(f)
        logger.info(f"Đã tải product encoder hiện có ({len(artifacts['product_encoder'].classes_)} products) từ Blob")
    else:
        artifacts['product_encoder'] = LabelEncoder()
        logger.info("Không tìm thấy product encoder trong Blob, tạo mới")

    # 4. Kiểm tra và tải interaction matrix từ Blob
    interaction_matrix_blob = container_client.get_blob_client(INTERACTION_MATRIX_BLOB_NAME)
    if interaction_matrix_blob.exists():
        download_blob_to_file(interaction_matrix_blob, interaction_matrix_path)
        artifacts['interaction_matrix'] = load_npz(interaction_matrix_path)
        logger.info(f"Đã tải interaction matrix hiện có từ Blob, shape: {artifacts['interaction_matrix'].shape}")
    else:
        logger.info("Không tìm thấy interaction matrix trong Blob, sẽ tạo từ dữ liệu mới")

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

        # Tạo document để lưu
        model_doc = {
            'id': str(uuid.uuid4()), # ID duy nhất cho mỗi phiên bản model
            'modelType': 'ALS', # Partition Key
            'timestamp': datetime.now(timezone.utc).isoformat(), # Thời gian tạo
            'modelData': model_bytes, # Dữ liệu model đã serialize (dạng bytes)
            # Lưu metadata về các artifacts liên quan trong Blob
            'userEncoderBlob': user_encoder_blob_name,
            'productEncoderBlob': product_encoder_blob_name,
            'interactionMatrixBlob': matrix_blob_name,
            # Thêm các thông số huấn luyện nếu cần
            'factors': FACTORS,
            'regularization': REGULARIZATION,
            'iterations': ITERATIONS
        }

        # Lưu vào Cosmos DB
        cosmos_container.create_item(body=model_doc)
        logger.info(f"Đã lưu model vào Cosmos DB, ID: {model_doc['id']}")
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
    # Không cần model_path nữa
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    blob_success = True
    cosmos_success = False

    try:
        # 1. Lưu user encoder vào Blob
        with open(user_encoder_path, 'wb') as f:
            pickle.dump(artifacts['user_encoder'], f)
        if not upload_file_to_blob(container_client, user_encoder_path, USER_ENCODER_BLOB_NAME):
            blob_success = False

        # 2. Lưu product encoder vào Blob
        with open(product_encoder_path, 'wb') as f:
            pickle.dump(artifacts['product_encoder'], f)
        if not upload_file_to_blob(container_client, product_encoder_path, PRODUCT_ENCODER_BLOB_NAME):
            blob_success = False

        # 3. Lưu interaction matrix vào Blob
        if 'interaction_matrix' in artifacts:
            save_npz(interaction_matrix_path, artifacts['interaction_matrix'])
            if not upload_file_to_blob(container_client, interaction_matrix_path, INTERACTION_MATRIX_BLOB_NAME):
                 blob_success = False
        else:
             logger.warning("Không có interaction matrix để lưu.")


        if blob_success:
             logger.info("Đã lưu encoder và matrix vào Azure Blob Storage")
             # 4. Lưu model vào Cosmos DB
             cosmos_success = save_model_to_cosmosdb(
                 artifacts['model'],
                 cosmos_container,
                 USER_ENCODER_BLOB_NAME,
                 PRODUCT_ENCODER_BLOB_NAME,
                 INTERACTION_MATRIX_BLOB_NAME # Truyền tên blob của matrix
            )
        else:
             logger.error("Lỗi khi lưu encoder/matrix vào Blob Storage, sẽ không lưu model vào Cosmos DB.")


        return blob_success and cosmos_success

    except Exception as e:
        logger.error(f"Lỗi khi lưu artifacts: {e}")
        return False

def run_batch_update():
    """Chạy job cập nhật batch"""
    logger.info("Bắt đầu job cập nhật batch...")

    # Kiểm tra cấu hình Azure Blob
    if not AZURE_CONNECTION_STRING:
        logger.error("AZURE_CONNECTION_STRING không được cấu hình")
        return

    # Khởi tạo Cosmos DB
    cosmos_container = initialize_cosmos_db()
    if not cosmos_container:
        logger.error("Không thể khởi tạo Cosmos DB, dừng job.")
        return

    try:
        # Khởi tạo Azure Blob Storage client
        blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
        blob_container_client = blob_service_client.get_container_client(AZURE_CONTAINER)

        # Kiểm tra xem có files mới không
        blob_list = list(blob_container_client.list_blobs(name_starts_with=NEW_DATA_PATH))
        if not blob_list:
            logger.info("Không có files tương tác mới, kết thúc job")
            return

        # Tạo thư mục tạm để lưu artifacts
        with tempfile.TemporaryDirectory() as temp_dir:
            # Tải artifacts hiện có (Model từ Cosmos, còn lại từ Blob)
            artifacts = load_existing_artifacts(blob_service_client, AZURE_CONTAINER, cosmos_container, temp_dir)

            # Tải dữ liệu tương tác mới từ Blob
            interactions_df = load_interaction_files(blob_container_client, NEW_DATA_PATH)

            # Cập nhật artifacts với dữ liệu mới và huấn luyện lại model
            updated_artifacts = update_artifacts_with_new_data(artifacts, interactions_df)

            # Lưu artifacts đã cập nhật (Model vào Cosmos, còn lại vào Blob)
            success = save_artifacts(
                updated_artifacts,
                blob_service_client,
                AZURE_CONTAINER,
                cosmos_container,
                temp_dir
            )

            if success:
                # Lưu trữ các files đã xử lý trong Blob
                archive_processed_files(blob_container_client, NEW_DATA_PATH, ARCHIVE_PATH)
                logger.info("Job cập nhật hoàn tất thành công")
            else:
                logger.error("Không thể lưu artifacts, hủy job")

    except Exception as e:
        logger.error(f"Lỗi khi chạy job cập nhật batch: {e}")

if __name__ == "__main__":
    run_batch_update() 