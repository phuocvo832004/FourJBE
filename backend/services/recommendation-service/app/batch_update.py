import logging
import os
import pickle
import tempfile
from datetime import datetime, timezone
from pathlib import Path
import uuid
import base64
import time
import io
from dotenv import load_dotenv
import numpy as np
import pandas as pd
from azure.storage.blob import BlobServiceClient, ContainerClient
from azure.cosmos import CosmosClient, PartitionKey
from implicit.als import AlternatingLeastSquares as ALSModelClass
from scipy.sparse import csr_matrix, load_npz, save_npz, lil_matrix
from sklearn.preprocessing import LabelEncoder
import gc

# Cố gắng giới hạn số luồng OpenBLAS
try:
    from threadpoolctl import threadpool_limits
    threadpool_limits(limits=1, user_api='blas')
    print("Successfully set OpenBLAS thread limit to 1.")
except ImportError:
    print("threadpoolctl not found, cannot set OpenBLAS thread limit. Consider installing it: pip install threadpoolctl")
except Exception as e:
    print(f"Error setting OpenBLAS thread limit: {e}")

load_dotenv()
# --- Cau hinh logging ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)

# --- Cau hinh Azure Blob Storage ---
AZURE_CONNECTION_STRING = os.getenv('AZURE_CONNECTION_STRING', '')
AZURE_CONTAINER = os.getenv('AZURE_CONTAINER', 'orderhistory')
USE_AZURE_STORAGE = os.getenv('USE_AZURE_STORAGE', 'false').lower() == 'true'
AZURE_USER_ENCODER_BLOB_NAME = os.getenv('AZURE_USER_ENCODER_BLOB_NAME', 'user_encoder.pkl')
AZURE_PRODUCT_ENCODER_BLOB_NAME = os.getenv('AZURE_PRODUCT_ENCODER_BLOB_NAME', 'product_encoder.pkl')
AZURE_INTERACTION_MATRIX_BLOB_NAME = os.getenv('AZURE_INTERACTION_MATRIX_BLOB_NAME', 'interaction_matrix.npz')


# --- Cau hinh Azure Cosmos DB ---
COSMOS_ENDPOINT = os.getenv('COSMOS_ENDPOINT')
COSMOS_KEY = os.getenv('COSMOS_KEY')
COSMOS_DATABASE_NAME = os.getenv('COSMOS_DATABASE_NAME', 'RecommendationDB')
COSMOS_CONTAINER_NAME = os.getenv('COSMOS_MODELS_CONTAINER_NAME', 'Models') 

# --- Đuong dan du lieu moi ---
NEW_DATA_PATH = os.getenv('NEW_DATA_PATH', 'processed-interactions/new/')
ARCHIVE_PATH = os.getenv('ARCHIVE_PATH', 'processed-interactions/archived/')

# --- Đuong dan artifacts ---
ARTIFACTS_PATH = os.getenv('ARTIFACTS_PATH', 'artifacts/')

# --- Cau hinh model ---
# TAM THOI GIAM ĐE TEST
FACTORS = int(os.getenv('ALS_FACTORS_TEST', '50'))  # So luong yeu to an (TANG TU 10 LEN 50)
REGULARIZATION = float(os.getenv('ALS_REGULARIZATION', '0.01'))  # He so regularization
ITERATIONS = int(os.getenv('ALS_ITERATIONS_TEST', '15'))  # So lan lap (TANG TU 2 LEN 15)
ALPHA = float(os.getenv('ALS_ALPHA', '15.0'))  # Alpha parameter (confidence scaling)

logger.info(f"ĐANG CHAY VOI FACTORS={FACTORS}, ITERATIONS={ITERATIONS}")

# Max chunk size for Cosmos DB items (Azure recommends < 2MB per document)
# Considering base64 encoding increases size, using a conservative 1MB for raw pickled data.
MAX_CHUNK_SIZE_BYTES = 1 * 1024 * 1024  # 1MB for pickled bytes

def download_blob_to_file(blob_client, file_path, max_retries=3, retry_delay=2):
    """
    Tai mot blob tu Azure Blob Storage ve tep cuc bo voi co che thu lai
    
    Args:
        blob_client: Azure blob client
        file_path (str): Đuong dan tep cuc bo đe luu
        max_retries (int): So lan thu lai toi đa
        retry_delay (int): Thoi gian cho giua cac lan thu lai (giay)
    
    Returns:
        bool: True neu thanh cong, False neu khong
    """
    for attempt in range(max_retries + 1):
        try:
            with open(file_path, "wb") as file:
                data = blob_client.download_blob()
                file.write(data.readall())
            return True
        except Exception as e:
            if "503" in str(e) or "timeout" in str(e).lower() or "connection" in str(e).lower():
                # Loi ket noi tam thoi
                if attempt < max_retries:
                    wait_time = retry_delay * (2 ** attempt)  # Exponential backoff
                    logger.warning(f"Loi ket noi tam thoi khi tai blob, thu lai sau {wait_time}s: {e}")
                    time.sleep(wait_time)
                    continue
            logger.error(f"Loi khi tai blob (lan {attempt+1}/{max_retries+1}): {e}")
            return False
    return False

def upload_file_to_blob(container_client, file_path, blob_name, max_retries=3, retry_delay=2):
    """
    Tai mot tep len Azure Blob Storage voi co che thu lai
    
    Args:
        container_client: Azure container client
        file_path (str): Đuong dan tep cuc bo đe tai len
        blob_name (str): Ten cua blob
        max_retries (int): So lan thu lai toi đa
        retry_delay (int): Thoi gian cho giua cac lan thu lai (giay)
        
    Returns:
        bool: True neu thanh cong, False neu khong
    """
    for attempt in range(max_retries + 1):
        try:
            blob_client = container_client.get_blob_client(blob_name)
            with open(file_path, "rb") as file:
                blob_client.upload_blob(file, overwrite=True)
            return True
        except Exception as e:
            if "503" in str(e) or "timeout" in str(e).lower() or "connection" in str(e).lower():
                # Loi ket noi tam thoi
                if attempt < max_retries:
                    wait_time = retry_delay * (2 ** attempt)  # Exponential backoff
                    logger.warning(f"Loi ket noi tam thoi khi tai len blob, thu lai sau {wait_time}s: {e}")
                    time.sleep(wait_time)
                    continue
            logger.error(f"Loi khi tai len blob (lan {attempt+1}/{max_retries+1}): {e}")
            return False
    return False

def load_interaction_files(container_client, new_data_path, batch_size=5, validate_data=True):
    """
    Tai tat ca cac files tuong tac moi tu Azure Blob Storage
    """
    # Thay vi load tat ca vao bo nho, xu ly tung lo va merge ngay
    result_df = None
    
    # Liet ke tat ca cac blobs trong đuong dan
    blob_list = list(container_client.list_blobs(name_starts_with=new_data_path))
    blob_count = len(blob_list)
    processed_count = 0
    invalid_count = 0
    
    # Xu ly theo lo
    for i in range(0, len(blob_list), batch_size):
        batch_blobs = blob_list[i:i+batch_size]
        batch_interactions = []
        
        logger.info(f"Xu ly lo {i//batch_size + 1}/{(len(blob_list) + batch_size - 1)//batch_size} ({len(batch_blobs)} files)")
        
        for blob in batch_blobs:
            try:
                blob_client = container_client.get_blob_client(blob.name)
                # Đoc file theo chunk thay vi toan bo vao memory
                df_new_interactions = pd.read_csv(
                    io.StringIO(blob_client.download_blob().readall().decode('utf-8')),
                    dtype={'user_id': str, 'product_id': str},  # Đinh nghia kieu du lieu truoc
                    usecols=lambda x: x in ['user_id', 'product_id', 'quantity']  # Chi lay cot can thiet
                )

                if validate_data:
                    # Bo cac dong khong hop le
                    df_new_interactions.dropna(subset=['user_id', 'product_id', 'quantity'], inplace=True)
                    if df_new_interactions.empty:
                        continue
                    
                    # Chuyen đoi va kiem tra
                    df_new_interactions['quantity'] = pd.to_numeric(df_new_interactions['quantity'], errors='coerce')
                    df_new_interactions = df_new_interactions[df_new_interactions['quantity'] > 0]
                    if df_new_interactions.empty:
                        continue
                        
                processed_count += len(df_new_interactions)
                batch_interactions.append(df_new_interactions)
            except Exception as e:
                logger.error(f"Loi khi xu ly blob {blob.name}: {e}")
                invalid_count += 1
        
        # Gop du lieu lo hien tai
        if batch_interactions:
            batch_df = pd.concat(batch_interactions, ignore_index=True)
            
            # Neu la lo đau tien, khoi tao result_df
            if result_df is None:
                result_df = batch_df
            else:
                # Gop voi ket qua hien tai
                result_df = pd.concat([result_df, batch_df], ignore_index=True)
            
            # Xoa bien tam
            del batch_df
            del batch_interactions
            gc.collect()
    
    logger.info(f"Đa đoc {blob_count} files, {processed_count} tuong tac hop le, bo qua {invalid_count} loi")
    
    # Neu khong co du lieu
    if result_df is None or result_df.empty:
        return pd.DataFrame()
    
    return result_df

def archive_processed_files(container_client, new_data_path, archive_path):
    """
    Di chuyen cac files đa xu ly sang thu muc luu tru
    
    Args:
        container_client: Azure container client
        new_data_path (str): Đuong dan chua cac files tuong tac moi
        archive_path (str): Đuong dan thu muc luu tru
        
    Returns:
        int: So luong files đa đuoc luu tru
    """
    # Tao đuong dan luu tru voi timestamp
    timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
    target_path = f"{archive_path}{timestamp}/"
    
    # Liet ke tat ca cac blobs trong đuong dan
    blob_list = container_client.list_blobs(name_starts_with=new_data_path)
    archived_count = 0
    
    for blob in blob_list:
        try:
            # Ten blob moi
            old_name = blob.name
            file_name = old_name.split('/')[-1]
            new_name = f"{target_path}{file_name}"
            
            # Tai noi dung
            source_blob = container_client.get_blob_client(old_name)
            data = source_blob.download_blob().readall()
            
            # Tao blob moi
            target_blob = container_client.get_blob_client(new_name)
            target_blob.upload_blob(data)
            
            # Xoa blob cu
            source_blob.delete_blob()
            
            archived_count += 1
        except Exception as e:
            logger.error(f"Loi khi luu tru blob {blob.name}: {e}")
    
    logger.info(f"Đa luu tru {archived_count} files vao {target_path}")
    return archived_count

def initialize_cosmos_db():
    """Khoi tao Cosmos DB client va tao database/container neu chua co."""
    if not COSMOS_ENDPOINT or not COSMOS_KEY:
        logger.error("COSMOS_ENDPOINT hoac COSMOS_KEY chua đuoc cau hinh.")
        return None
    try:
        client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
        database = client.create_database_if_not_exists(id=COSMOS_DATABASE_NAME)
        container = database.create_container_if_not_exists(
            id=COSMOS_CONTAINER_NAME,
            partition_key=PartitionKey(path="/partition_key"), # Su dung partition_key giong uploadmodelcosmos.py
            offer_throughput=400
        )
        logger.info(f"Cosmos DB database '{COSMOS_DATABASE_NAME}' va container '{COSMOS_CONTAINER_NAME}' đa san sang.")
        return container
    except Exception as e:
        logger.error(f"Loi khi khoi tao Cosmos DB: {e}")
        return None

def load_latest_model_from_cosmos(cosmos_container):
    """
    Tai model va metadata moi nhat tu Cosmos DB bang cach su dung model descriptor.
    
    Args:
        cosmos_container: Cosmos DB container client
        
    Returns:
        tuple: (model_descriptor_doc, model) hoac (None, None) neu khong tim thay
    """
    if not cosmos_container:
        logger.error("Cosmos container khong đuoc cung cap.")
        return None, None
    try:
        # 1. Truy van modelDescriptor moi nhat, active
        DESCRIPTOR_PARTITION_KEY_VALUE = "als_model_descriptor"
        query = (
            "SELECT TOP 1 * FROM c "
            "WHERE c.documentType = 'modelDescriptor' AND c.partition_key = @desc_pk AND c.status = 'active' "
            "ORDER BY c.timestamp DESC"
        )
        query_params = [dict(name="@desc_pk", value=DESCRIPTOR_PARTITION_KEY_VALUE)]
        
        descriptors = list(cosmos_container.query_items(
            query=query,
            parameters=query_params,
            enable_cross_partition_query=True # False vi đang query tren partition_key cu the
        ))
        
        if not descriptors:
            logger.info("Khong tim thay model descriptor nao active trong Cosmos DB.")
            # Fallback: thu tim descriptor moi nhat khong ke status (neu logic yeu cau)
            # query_fallback = "SELECT TOP 1 * FROM c WHERE c.documentType = 'modelDescriptor' AND c.partition_key = @desc_pk ORDER BY c.timestamp DESC"
            # descriptors = list(cosmos_container.query_items(query=query_fallback, parameters=query_params, enable_cross_partition_query=False))
            # if not descriptors:
            #    logger.info("Khong tim thay model descriptor nao trong Cosmos DB (ke ca inactive).")
            #    return None, None
            return None, None # Hien tai, chi lay active

        model_descriptor_doc = descriptors[0]
        logger.info(f"Tim thay model descriptor: ID={model_descriptor_doc.get('id')}, Timestamp={model_descriptor_doc.get('timestamp')}")
        
        model_chunk_partition_key = model_descriptor_doc.get('modelChunkPartitionKey')
        if not model_chunk_partition_key:
            logger.error(f"Model descriptor (ID: {model_descriptor_doc.get('id')}) khong co 'modelChunkPartitionKey'.")
            return model_descriptor_doc, None

        logger.info(f"Tai model tu chunks voi modelChunkPartitionKey: '{model_chunk_partition_key}'")
        
        # 2. Truy van tat ca cac chunks cua model nay, sap xep theo chunk_index
        chunk_query = "SELECT * FROM c WHERE c.partition_key = @chunk_pk AND c.documentType = 'modelChunk' ORDER BY c.chunk_index ASC"
        chunk_query_params = [dict(name="@chunk_pk", value=model_chunk_partition_key)]
        
        model_chunk_docs = list(cosmos_container.query_items(
            query=chunk_query,
            parameters=chunk_query_params,
            enable_cross_partition_query=True # False vi đang query tren partition_key cu the
        ))
        
        if not model_chunk_docs:
            logger.error(f"Khong tim thay chunks model cho modelChunkPartitionKey '{model_chunk_partition_key}'")
            return model_descriptor_doc, None
        
        # Ghep cac chunks lai
        try:
            # Sap xep lai phong truong hop DB khong hoan toan tuan thu ORDER BY (it kha nang)
            # model_chunk_docs.sort(key=lambda doc: doc['chunk_index'])
            base64_encoded_model_str = "".join([chunk_doc['file_chunk'] for chunk_doc in model_chunk_docs])
            model_bytes = base64.b64decode(base64_encoded_model_str)
            model = pickle.loads(model_bytes)
            logger.info(f"Đa tai model thanh cong tu {len(model_chunk_docs)} chunks cho descriptor {model_descriptor_doc.get('id')}")
            return model_descriptor_doc, model
        except KeyError as ke:
            logger.error(f"Loi khi giai ma model tu chunks: Thieu key 'file_chunk' hoac 'chunk_index'. {ke}")
            return model_descriptor_doc, None
        except Exception as e:
            logger.error(f"Loi khi giai ma model tu chunks: {e}")
            return model_descriptor_doc, None
            
    except Exception as e:
        logger.error(f"Loi nghiem trong khi tai model tu Cosmos DB: {e}")
        import traceback
        logger.error(traceback.format_exc())
        return None, None

def load_existing_artifacts(blob_service_client, container_name, cosmos_container, temp_dir):
    """
    Tai cac artifacts hien co: model tu Cosmos DB, con lai tu Blob Storage
    """
    container_client = blob_service_client.get_container_client(container_name)
    artifacts = {}

    # 1. Tai model va model_descriptor tu Cosmos DB
    model_descriptor_doc, model = load_latest_model_from_cosmos(cosmos_container)
    
    if model:
        artifacts['model'] = model
        logger.info("Đa tai model tu Cosmos DB")
        if model_descriptor_doc:
            logger.info(f"Su dung model descriptor ID: {model_descriptor_doc.get('id')}")
    else:
        # Tao mo hinh moi neu khong tim thay trong Cosmos DB
        artifacts['model'] = ALSModelClass(
            factors=FACTORS,
            regularization=REGULARIZATION,
            iterations=ITERATIONS,
            num_threads=0,  # Su dung tat ca cores cho container
            random_state=42
        )
        logger.info("Khong tim thay model trong Cosmos DB hoac model descriptor, tao model ALS moi.")
        model_descriptor_doc = None # Đam bao khong co descriptor neu model moi đuoc tao

    # Xac đinh ten blob cho cac artifacts khac tu model_descriptor_doc hoac su dung mac đinh
    user_encoder_blob_name = AZURE_USER_ENCODER_BLOB_NAME
    product_encoder_blob_name = AZURE_PRODUCT_ENCODER_BLOB_NAME
    interaction_matrix_blob_name = AZURE_INTERACTION_MATRIX_BLOB_NAME
    
    if model_descriptor_doc:
        user_encoder_blob_name = model_descriptor_doc.get('userEncoderBlobName', AZURE_USER_ENCODER_BLOB_NAME)
        product_encoder_blob_name = model_descriptor_doc.get('productEncoderBlobName', AZURE_PRODUCT_ENCODER_BLOB_NAME)
        interaction_matrix_blob_name = model_descriptor_doc.get('interactionMatrixBlobName', AZURE_INTERACTION_MATRIX_BLOB_NAME)
        logger.info(f"Su dung ten blob tu model descriptor:")
        logger.info(f"- User encoder: {user_encoder_blob_name}")
        logger.info(f"- Product encoder: {product_encoder_blob_name}")
        logger.info(f"- Interaction matrix: {interaction_matrix_blob_name}")
    else:
        logger.info("Khong co model descriptor hoac khong tim thay model, su dung ten blob mac đinh cho artifacts.")

    # Đuong dan tep cuc bo cho cac artifacts khac
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    # 2. Kiem tra va tai user encoder tu Blob
    user_encoder_blob = container_client.get_blob_client(user_encoder_blob_name)
    if user_encoder_blob.exists():
        if download_blob_to_file(user_encoder_blob, user_encoder_path):
            with open(user_encoder_path, 'rb') as f:
                artifacts['user_encoder'] = pickle.load(f)
            logger.info(f"Đa tai user encoder ({len(artifacts['user_encoder'].classes_)} users) tu Blob: {user_encoder_blob_name}")
        else:
            logger.error(f"Loi khi tai user encoder tu Blob: {user_encoder_blob_name}. Tao moi.")
            artifacts['user_encoder'] = LabelEncoder()
    else:
        artifacts['user_encoder'] = LabelEncoder()
        logger.info(f"Khong tim thay user encoder trong Blob tai {user_encoder_blob_name}, tao moi.")

    # 3. Kiem tra va tai product encoder tu Blob
    product_encoder_blob = container_client.get_blob_client(product_encoder_blob_name)
    if product_encoder_blob.exists():
        if download_blob_to_file(product_encoder_blob, product_encoder_path):
            with open(product_encoder_path, 'rb') as f:
                artifacts['product_encoder'] = pickle.load(f)
            logger.info(f"Đa tai product encoder ({len(artifacts['product_encoder'].classes_)} products) tu Blob: {product_encoder_blob_name}")
        else:
            logger.error(f"Loi khi tai product encoder tu Blob: {product_encoder_blob_name}. Tao moi.")
            artifacts['product_encoder'] = LabelEncoder()
    else:
        artifacts['product_encoder'] = LabelEncoder()
        logger.info(f"Khong tim thay product encoder trong Blob tai {product_encoder_blob_name}, tao moi.")

    # 4. Kiem tra va tai interaction matrix tu Blob
    interaction_matrix_blob = container_client.get_blob_client(interaction_matrix_blob_name)
    if interaction_matrix_blob.exists():
        if download_blob_to_file(interaction_matrix_blob, interaction_matrix_path):
            artifacts['interaction_matrix'] = load_npz(interaction_matrix_path)
            logger.info(f"Đa tai interaction matrix tu Blob: {interaction_matrix_blob_name}, shape: {artifacts['interaction_matrix'].shape}")
        else:
            logger.error(f"Loi khi tai interaction matrix tu Blob: {interaction_matrix_blob_name}. Se tao tu du lieu moi neu co.")
            # Khong tao matrix rong o đay, đe logic update_artifacts_with_new_data xu ly
    else:
        logger.info(f"Khong tim thay interaction matrix trong Blob tai {interaction_matrix_blob_name}, se tao tu du lieu moi neu co.")

    return artifacts

def update_artifacts_with_new_data(artifacts, interactions_df):
    """
    Cap nhat artifacts hien co voi du lieu tuong tac moi
    """
    if interactions_df.empty:
        logger.info("Khong co du lieu moi de cap nhat")
        return artifacts
    
    user_encoder = artifacts['user_encoder']
    product_encoder = artifacts['product_encoder']
    
    # Chi lay cac cot can thiet va giam bo nho
    df = interactions_df[['user_id', 'product_id', 'quantity']].copy()
    
    # Chuyen đoi user_id thanh chuoi
    df['user_id'] = df['user_id'].astype(str)
    
    # Lay unique users/products voi hieu suat cao hon
    unique_users = np.unique(df['user_id'].values)
    unique_products = np.unique(df['product_id'].values)
    
    # Xu ly encoders
    if hasattr(user_encoder, 'classes_') and len(user_encoder.classes_) > 0:
        # Chuyen sang numpy array đe xu ly hieu qua
        user_encoder.classes_ = np.array([str(u) for u in user_encoder.classes_])
        all_users = np.unique(np.concatenate([user_encoder.classes_, unique_users]))
        user_encoder.classes_ = all_users
    else:
        user_encoder.fit(unique_users)
    
    if hasattr(product_encoder, 'classes_') and len(product_encoder.classes_) > 0:
        product_encoder.classes_ = np.array([str(p) for p in product_encoder.classes_])
        all_products = np.unique(np.concatenate([product_encoder.classes_, unique_products]))
        product_encoder.classes_ = all_products
    else:
        product_encoder.fit(unique_products)
    
    # Chuyen đoi user_id va product_id thanh indices
    df['user_idx'] = user_encoder.transform(df['user_id'])
    df['product_idx'] = product_encoder.transform(df['product_id'])
    
    # Tao ma tran tuong tac moi
    user_count = len(user_encoder.classes_)
    product_count = len(product_encoder.classes_)
    
    # Tao ma tran moi
    rows = df['user_idx'].values
    cols = df['product_idx'].values
    data = df['quantity'].values
    
    # Giam bot bo nho bang cach xoa DataFrame sau khi trich xuat du lieu
    del df
    gc.collect()
    
    # Tao ma tran thua dang COO truoc, roi chuyen sang CSR - hieu qua hon cho viec xay dung
    new_matrix = csr_matrix((data, (rows, cols)), shape=(user_count, product_count))
    new_matrix.eliminate_zeros()  # Loai bo zeros đe giam bo nho
    
    # Neu đa co ma tran cu, ket hop voi ma tran moi
    if 'interaction_matrix' in artifacts:
        old_matrix = artifacts['interaction_matrix']
        
        # Xu ly neu kich thuoc khac nhau
        if old_matrix.shape != new_matrix.shape:
            logger.info(f"Thay đoi kich thuoc ma tran tu {old_matrix.shape} thanh {new_matrix.shape}")
            
            # Su dung lil_matrix cho viec gan phan tu hieu qua
            combined_matrix = lil_matrix((user_count, product_count))
            
            # Sao chep du lieu tu ma tran cu
            # Kich thuoc cua old_matrix
            prev_rows, prev_cols = old_matrix.shape
            
            # Sao chep theo block đe tiet kiem bo nho
            # Copy phan chung cua old_matrix vao combined_matrix
            # Đảm bảo không vượt quá kích thước của old_matrix hoặc combined_matrix
            rows_to_copy = min(prev_rows, user_count)
            cols_to_copy = min(prev_cols, product_count)

            block_size = 10000  # Kich thuoc block phu hop
            for i in range(0, rows_to_copy, block_size):
                end_i = min(i + block_size, rows_to_copy)
                # Copy từ old_matrix (có thể nhỏ hơn) vào combined_matrix (lớn hơn hoặc bằng)
                combined_matrix[i:end_i, :cols_to_copy] = old_matrix[i:end_i, :cols_to_copy]
                
                # Giai phong bo nho thuong xuyen
                if i % (block_size * 5) == 0:
                    gc.collect()
            
            # Chuyen sang CSR cho phep tinh hieu qua
            combined_matrix = combined_matrix.tocsr()
            
            # Cong ma tran moi (new_matrix đã có shape (user_count, product_count))
            combined_matrix = combined_matrix + new_matrix
            
            # Loai bo so 0
            combined_matrix.eliminate_zeros()
        else:
            # Cong truc tiep neu kich thuoc giong nhau
            combined_matrix = old_matrix + new_matrix
            combined_matrix.eliminate_zeros()
        
        # Xoa ma tran cu ngay khi khong can nua đe giai phong bo nho
        del old_matrix
        gc.collect()
        
        artifacts['interaction_matrix'] = combined_matrix
        logger.info(f"Đa ket hop ma tran cu va moi, kich thuoc moi: {combined_matrix.shape}, nnz: {combined_matrix.nnz}")
    else:
        artifacts['interaction_matrix'] = new_matrix
        logger.info(f"Đa tao ma tran tuong tac moi, kich thuoc: {new_matrix.shape}, nnz: {new_matrix.nnz}")
    
    # --- Logic huấn luyện lại model ---
    model = artifacts['model']
    
    # Xác định ma trận nào sẽ được sử dụng để huấn luyện
    matrix_to_train_on = None
    if 'combined_matrix' in locals() and combined_matrix is not None: # Ưu tiên combined_matrix nếu nó tồn tại
        matrix_to_train_on = combined_matrix
        logger.info(f"Su dung combined_matrix (shape: {matrix_to_train_on.shape}, nnz: {matrix_to_train_on.nnz}) de huan luyen.")
    elif 'interaction_matrix' in artifacts and artifacts['interaction_matrix'] is not None:
        # Trường hợp không có dữ liệu mới (interactions_df rỗng) hoặc không có old_matrix ban đầu (chỉ có new_matrix)
        matrix_to_train_on = artifacts['interaction_matrix']
        logger.info(f"Su dung artifacts['interaction_matrix'] (shape: {matrix_to_train_on.shape}, nnz: {matrix_to_train_on.nnz}) de huan luyen.")
    else:
        logger.warning("Khong co ma tran tuong tac nao hop le de huan luyen.")

    if matrix_to_train_on is None or matrix_to_train_on.nnz == 0:
        logger.warning("Ma tran tuong tac de huan luyen rong hoac khong co du lieu (nnz=0). Bo qua buoc huan luyen model.")
        # Giữ nguyên model hiện tại (có thể là model cũ hoặc model mới chưa được huấn luyện)
    else:
        logger.info("Du lieu moi/cap nhat ton tai. Se khoi tao va huan luyen model ALS moi.")
        model_to_train = ALSModelClass( 
            factors=FACTORS,
            regularization=REGULARIZATION,
            iterations=ITERATIONS,
            num_threads=1, # Đặt num_threads=1 ở đây; threadpool_limits đã giới hạn OpenBLAS
            random_state=42
        )
        logger.info(f"Model ALS moi duoc khoi tao cho huan luyen: factors={model_to_train.factors}, regularization={model_to_train.regularization}, iterations={model_to_train.iterations}, num_threads={model_to_train.num_threads}")

        logger.info(f"Ma tran goc dung de huan luyen: shape={matrix_to_train_on.shape}, dtype={matrix_to_train_on.dtype}, nnz={matrix_to_train_on.nnz}")

        # Chuyển đổi sang ma trận confidence sử dụng ALPHA
        # implicit yêu cầu ma trận confidence có kiểu dữ liệu 'double' (float64)
        logger.info(f"Ap dung ALPHA={ALPHA} de tao ma tran confidence.")
        confidence_matrix = (matrix_to_train_on.astype(np.float64) * ALPHA).astype(np.float64)
        confidence_matrix_csr = confidence_matrix.tocsr() # Đảm bảo là CSR
        
        logger.info(f"Ma tran confidence sau khi ap dung ALPHA: shape={confidence_matrix_csr.shape}, dtype={confidence_matrix_csr.dtype}, nnz={confidence_matrix_csr.nnz}")
        logger.info(f"DEBUG: confidence_matrix_csr.data.dtype: {confidence_matrix_csr.data.dtype}")
        logger.info(f"DEBUG: confidence_matrix_csr.indices.dtype: {confidence_matrix_csr.indices.dtype}")
        logger.info(f"DEBUG: confidence_matrix_csr.indptr.dtype: {confidence_matrix_csr.indptr.dtype}")

        # Đảm bảo indices và indptr là int32
        if confidence_matrix_csr.indices.dtype != np.int32:
            logger.warning(f"Chuyen đoi confidence_matrix_csr.indices tu {confidence_matrix_csr.indices.dtype} sang np.int32")
            confidence_matrix_csr.indices = confidence_matrix_csr.indices.astype(np.int32)
        if confidence_matrix_csr.indptr.dtype != np.int32:
            logger.warning(f"Chuyen đoi confidence_matrix_csr.indptr tu {confidence_matrix_csr.indptr.dtype} sang np.int32")
            confidence_matrix_csr.indptr = confidence_matrix_csr.indptr.astype(np.int32)

        # Them kiem tra NaN/Inf cho du lieu cua ma tran confidence
        if confidence_matrix_csr.nnz > 0:
            data_min = np.min(confidence_matrix_csr.data)
            data_max = np.max(confidence_matrix_csr.data)
            data_mean = np.mean(confidence_matrix_csr.data)
            logger.info(f"Stats for confidence_matrix_csr.data: min={data_min}, max={data_max}, mean={data_mean}")
            
            has_nans = np.isnan(confidence_matrix_csr.data).any()
            has_infs = np.isinf(confidence_matrix_csr.data).any()
            logger.info(f"Confidence matrix data has NaNs: {has_nans}")
            logger.info(f"Confidence matrix data has Infs: {has_infs}")
            
            if has_nans or has_infs:
                logger.error("ERROR: Confidence matrix data contains NaN or Inf values! This is a critical issue.")
                # Có thể xem xét dừng ở đây hoặc thực hiện hành động khác
        else:
            logger.info("Confidence matrix has no non-zero elements to check for NaN/Inf in its data array.")

        # Kiểm tra sự nhất quán về kích thước với encoders
        num_users_encoder = len(user_encoder.classes_)
        num_products_encoder = len(product_encoder.classes_)
        num_users_matrix, num_products_matrix = confidence_matrix_csr.shape

        if num_users_encoder != num_users_matrix or num_products_encoder != num_products_matrix:
            logger.warning(
                f"KICH THUOC KHONG KHOP: "
                f"Encoder Users: {num_users_encoder}, Matrix Rows (Users): {num_users_matrix}. "
                f"Encoder Products: {num_products_encoder}, Matrix Cols (Products): {num_products_matrix}."
            )
            # Đây có thể là một vấn đề nghiêm trọng, cần xem xét kỹ lưỡng logic cập nhật encoder và ma trận.
            # Tuy nhiên, hiện tại vẫn tiếp tục huấn luyện.
        else:
            logger.info("Kich thuoc ma tran huan luyen khop voi encoders.")

        logger.info(f"Bat dau huan luyen model ALS (moi khoi tao) voi factors={model_to_train.factors}, regularization={model_to_train.regularization}, iterations={model_to_train.iterations}, num_threads={model_to_train.num_threads}...")
        fit_start_time = time.time()
        model_to_train.fit(confidence_matrix_csr) # Huấn luyện trên ma trận confidence
        fit_duration = time.time() - fit_start_time
        artifacts['model'] = model_to_train # Cập nhật model đã huấn luyện MỚI vào artifacts
        logger.info(f"Đa huan luyen lai mo hinh ALS (moi khoi tao) thanh cong trong {fit_duration:.2f} giay.")

        # Kiểm tra model ngay sau khi huấn luyện
        try:
            logger.info("Kiem tra model (moi huan luyen) sau khi huan luyen...")
            if confidence_matrix_csr.shape[0] > 0: # Đảm bảo có user để test
                # Lấy một user_idx ngẫu nhiên để test (nếu có nhiều hơn 1 user)
                idx_to_test = 0
                if confidence_matrix_csr.shape[0] > 1:
                    idx_to_test = np.random.randint(0, confidence_matrix_csr.shape[0] - 1)
                
                logger.info(f"Thu recommend cho user_idx: {idx_to_test}")
                
                # Lấy vector tương tác của user đó từ ma trận confidence_matrix_csr (đã dùng để fit)
                user_items_for_recommend = confidence_matrix_csr[idx_to_test]
                
                # Gọi hàm recommend
                # filter_already_liked_items=False để có thể thấy các item đã tương tác (nếu model gợi ý lại)
                test_ids, test_scores = model_to_train.recommend( # Sử dụng model_to_train
                    userid=idx_to_test,
                    user_items=user_items_for_recommend, 
                    N=10,
                    filter_already_liked_items=False 
                )
                
                logger.info(f"Ket qua test recommend: ids={test_ids[:5]}, scores={test_scores[:5]}")
                
                # Cảnh báo nếu tất cả scores là 0 VÀ user đó có tương tác
                if user_items_for_recommend.nnz > 0 and np.all(np.isclose(test_scores, 0)):
                    logger.warning("CANH BAO: Tat ca scores la 0 cho user co tuong tac. Model co the khong hoc đuoc gi hoac co van đe.")
                elif user_items_for_recommend.nnz == 0:
                    logger.info("User test khong co tuong tac nao trong ma tran confidence.")
                else:
                    logger.info("Model test recommend co ve ổn! Scores khac 0.")
            else:
                logger.info("Khong co user nao trong ma tran confidence de thuc hien test recommend.")
        except Exception as e_test:
            logger.error(f"Loi khi test model sau khi huan luyen: {e_test}")
            import traceback
            logger.error(traceback.format_exc())

    # Chạy GC một lần nữa trước khi trả về kết quả
    gc.collect()
    return artifacts

def save_model_to_cosmosdb(model, cosmos_container, user_encoder_blob_name, product_encoder_blob_name, matrix_blob_name):
    """
    Serializes the model, saves it in chunks to Cosmos DB, and then saves a model descriptor document.
    Model chunks will use a unique 'model_chunk_partition_key'.
    The model descriptor will point to this 'model_chunk_partition_key'.
    """
    logger.info("Bat đau luu model va descriptor vao Cosmos DB.")

    # 1. Serialize the model
    try:
        serialized_model = pickle.dumps(model)
    except Exception as e:
        logger.error(f"Khong the serialize model: {e}")
        return False, None
    
    model_byte_stream = io.BytesIO(serialized_model)
    model_size = len(serialized_model)
    logger.info(f"Model đa đuoc serialize. Kich thuoc: {model_size} bytes.")

    # 2. Define a unique partition key for this model's chunks
    model_id_suffix = uuid.uuid4().hex[:8]
    timestamp_str = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
    # This key will be used as the partition_key for the chunk documents
    model_chunk_partition_key = f"alsmodel_chunks_{timestamp_str}_{model_id_suffix}"
    logger.info(f"Đa tao modelChunkPartitionKey (cho truong partition_key cua chunks): {model_chunk_partition_key}")

    # 3. Split into chunks and save to Cosmos DB
    chunk_index = 0
    saved_chunk_ids_and_pks = [] 

    while True:
        chunk_data_bytes = model_byte_stream.read(MAX_CHUNK_SIZE_BYTES) # Read raw bytes
        if not chunk_data_bytes:
            break

        encoded_chunk_data_str = base64.b64encode(chunk_data_bytes).decode('utf-8') # Base64 encode the chunk
        
        chunk_doc_id = f"chunk_{model_chunk_partition_key}_{chunk_index}"
        chunk_doc = {
            'id': chunk_doc_id,
            'partition_key': model_chunk_partition_key, # This model's unique chunk partition key
            'documentType': 'modelChunk',
            'chunk_index': chunk_index,
            'total_chunks': -1,  # Sẽ được cập nhật sau khi biết tổng số chunks
            'file_chunk': encoded_chunk_data_str,
            'timestamp': datetime.now(timezone.utc).isoformat()
        }
        
        try:
            logger.info(f"Saving chunk {chunk_index} for PK {model_chunk_partition_key}. "
                        f"Raw bytes length: {len(chunk_data_bytes) if chunk_data_bytes else 'N/A'}. "
                        f"Encoded string length: {len(encoded_chunk_data_str) if encoded_chunk_data_str else 'N/A'}.")
            if chunk_data_bytes and not encoded_chunk_data_str:
                logger.warning(f"CHUNK {chunk_index} (PK {model_chunk_partition_key}): encoded_chunk_data_str is EMPTY despite raw_bytes length {len(chunk_data_bytes)}!")
            elif not chunk_data_bytes and chunk_index < (model_size / MAX_CHUNK_SIZE_BYTES) -1 : # Heuristic check
                 logger.warning(f"CHUNK {chunk_index} (PK {model_chunk_partition_key}): raw_bytes is EMPTY unexpectedly before last chunk.")

            cosmos_container.create_item(body=chunk_doc)
            logger.info(f"Đa luu model chunk {chunk_index} voi id {chunk_doc_id} vao Cosmos DB duoi partition_key '{model_chunk_partition_key}'.")
            saved_chunk_ids_and_pks.append({'id': chunk_doc_id, 'pk': model_chunk_partition_key})
            chunk_index += 1
        except Exception as e:
            logger.error(f"Khong the luu model chunk {chunk_index} (id: {chunk_doc_id}) vao Cosmos DB: {e}")
            # TODO: Consider cleanup of already saved chunks for this model_chunk_partition_key
            return False, None 

    if chunk_index == 0 and model_size > 0:
        logger.error("Kich thuoc model > 0 nhung khong co chunk nao đuoc tao. Đay la mot loi.")
        return False, None
    elif model_size == 0 and chunk_index == 0:
         logger.info("Kich thuoc model la 0, khong co chunk nao đuoc tao (binh thuong đoi voi model rong).")
    else:
        logger.info(f"Đa luu thanh cong {chunk_index} model chunks vao Cosmos DB cho partition_key '{model_chunk_partition_key}'.")
    
    # Update total_chunks for each saved chunk
    if chunk_index > 0:
        logger.info(f"Cap nhat total_chunks={chunk_index} cho {len(saved_chunk_ids_and_pks)} chunks đa luu.")
        update_success_count = 0
        update_fail_count = 0
        
        for item_ref in saved_chunk_ids_and_pks:
            try:
                # Đọc item hiện tại với retry
                item_to_update = None
                read_successful = False
                max_read_retries = 3
                for attempt_read in range(max_read_retries):
                    try:
                        item_to_update = cosmos_container.read_item(item=item_ref['id'], partition_key=item_ref['pk'])
                        logger.debug(f"Successfully read chunk {item_ref['id']} for update on read attempt {attempt_read + 1}.")
                        read_successful = True
                        break 
                    except Exception as read_err:
                        is_not_found_error = False
                        # Kiểm tra xem có phải lỗi NotFound không
                        if hasattr(read_err, 'status_code') and read_err.status_code == 404:
                            is_not_found_error = True
                        elif "NotFound" in str(read_err) or "Non-Existent Resource" in str(read_err): # Kiểm tra thông điệp lỗi
                            is_not_found_error = True
                        
                        log_msg_prefix = f"Chunk {item_ref['id']}"
                        if is_not_found_error:
                            log_msg_prefix += " not found"
                        else:
                            log_msg_prefix += f" error reading ({type(read_err).__name__})"

                        logger.warning(f"{log_msg_prefix} on read attempt {attempt_read + 1}/{max_read_retries}: {read_err}. Retrying...")
                        
                        if attempt_read < max_read_retries - 1:
                            time.sleep(1 * (2**attempt_read)) # Chờ với thời gian tăng dần: 1s, 2s
                        else: # Lần thử cuối cùng thất bại
                            logger.error(f"Failed to read chunk {item_ref['id']} after {max_read_retries} read attempts: {read_err}")
                
                if not read_successful:
                    update_fail_count += 1 # Tăng số lỗi nếu không đọc được chunk
                    continue # Chuyển sang chunk tiếp theo

                # Cập nhật trường total_chunks
                item_to_update['total_chunks'] = chunk_index
                
                # Thực hiện cập nhật với retry logic
                max_retries = 3
                for retry in range(max_retries):
                    try:
                        cosmos_container.replace_item(item=item_ref['id'], body=item_to_update)
                        logger.debug(f"Đa cap nhat chunk {item_ref['id']} voi total_chunks={chunk_index}.")
                        update_success_count += 1
                        break
                    except Exception as update_err:
                        if retry < max_retries - 1:
                            logger.warning(f"Lỗi khi cập nhật chunk {item_ref['id']}, thử lại ({retry+1}/{max_retries}): {update_err}")
                            time.sleep(1)  # Chờ 1 giây trước khi thử lại
                        else:
                            logger.error(f"Khong the cap nhat chunk {item_ref['id']} voi total_chunks sau {max_retries} lần thử: {update_err}")
                            update_fail_count += 1
            except Exception as e:
                logger.error(f"Lỗi không xác định khi cập nhật chunk {item_ref['id']}: {e}")
                update_fail_count += 1
                
        logger.info(f"Kết quả cập nhật: {update_success_count} thành công, {update_fail_count} thất bại")
        
        # Nếu có nhiều hơn 20% chunks không được cập nhật, cân nhắc việc trả về False
        if update_fail_count > 0 and (update_fail_count / len(saved_chunk_ids_and_pks) > 0.2):
            logger.warning(f"Có {update_fail_count} / {len(saved_chunk_ids_and_pks)} chunks không thể cập nhật (> 20%). Có thể sẽ gây vấn đề khi tải model.")
            # Hiện tại vẫn sẽ tiếp tục, nhưng bạn có thể muốn trả về False ở đây

    # 4. Create and save the model descriptor document
    descriptor_id = f"desc_{timestamp_str}_{model_id_suffix}"
    # This is the partition_key for descriptor documents, allowing to query all descriptors
    DESCRIPTOR_PARTITION_KEY_VALUE = "als_model_descriptor" 
    model_descriptor_doc = {
        'id': descriptor_id,
        'partition_key': DESCRIPTOR_PARTITION_KEY_VALUE, 
        'documentType': 'modelDescriptor',
        'timestamp': datetime.now(timezone.utc).isoformat(), 
        'modelChunkPartitionKey': model_chunk_partition_key, # Points to the partition_key of the chunks
        'userEncoderBlobName': user_encoder_blob_name,
        'productEncoderBlobName': product_encoder_blob_name,
        'interactionMatrixBlobName': matrix_blob_name,
        'modelSizeBytes': model_size,
        'modelTotalChunks': chunk_index,
        'status': 'active', # Default new models to active
        'training_params': {
            'factors': FACTORS, 
            'regularization': REGULARIZATION, 
            'iterations': ITERATIONS, 
            'alpha': ALPHA 
        }
    }

    try:
        cosmos_container.create_item(body=model_descriptor_doc)
        logger.info(f"Đa luu thanh cong model descriptor vao Cosmos DB voi id: {descriptor_id}, modelChunkPartitionKey: {model_chunk_partition_key}")
    except Exception as e:
        logger.error(f"Khong the luu model descriptor (id: {descriptor_id}) vao Cosmos DB: {e}")
        return False, None
        
    return True, model_chunk_partition_key

def save_artifacts(artifacts, blob_service_client, container_name, cosmos_container, temp_dir):
    """
    Luu artifacts: model vao Cosmos DB, con lai vao Azure Blob Storage
    """
    container_client = blob_service_client.get_container_client(container_name)

    # Đuong dan tep cuc bo
    user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
    product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
    interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"

    blob_success = True
    cosmos_success = False

    try:
        # 1. Luu user encoder vao Blob
        with open(user_encoder_path, 'wb') as f:
            pickle.dump(artifacts['user_encoder'], f)
        if not upload_file_to_blob(container_client, user_encoder_path, AZURE_USER_ENCODER_BLOB_NAME):
            blob_success = False

        # 2. Luu product encoder vao Blob
        with open(product_encoder_path, 'wb') as f:
            pickle.dump(artifacts['product_encoder'], f)
        if not upload_file_to_blob(container_client, product_encoder_path, AZURE_PRODUCT_ENCODER_BLOB_NAME):
            blob_success = False

        # 3. Luu interaction matrix vao Blob
        if 'interaction_matrix' in artifacts:
            save_npz(interaction_matrix_path, artifacts['interaction_matrix'])
            if not upload_file_to_blob(container_client, interaction_matrix_path, AZURE_INTERACTION_MATRIX_BLOB_NAME):
                 blob_success = False
        else:
             logger.warning("Khong co interaction matrix đe luu.")

        if blob_success:
            logger.info("Đa luu encoder va matrix vao Azure Blob Storage")
            # 4. Luu model vao Cosmos DB theo cach moi
            cosmos_success, model_chunk_key = save_model_to_cosmosdb(
                artifacts['model'],
                cosmos_container,
                AZURE_USER_ENCODER_BLOB_NAME,
                AZURE_PRODUCT_ENCODER_BLOB_NAME,
                AZURE_INTERACTION_MATRIX_BLOB_NAME
            )

            if cosmos_success:
                logger.info(f"Model đa đuoc luu vao Cosmos DB voi model_chunk_key: {model_chunk_key}")
                # Neu ban can lam gi đo voi model_chunk_key, hay lam o đay
            else:
                logger.error("Khong the luu model vao Cosmos DB.")
        else:
            logger.error("Loi khi luu encoder/matrix vao Blob Storage, se khong luu model vao Cosmos DB.")
            cosmos_success = False # Đam bao trang thai cuoi cung phan anh loi
        
        return blob_success and cosmos_success
    except Exception as e:
        logger.error(f"Loi khi luu artifacts: {e}")
        return False

def run_batch_update():
    """Chay job cap nhat batch"""
    start_time = time.time()
    job_metrics = {
        "files_processed": 0,
        "interactions_processed": 0,
        "invalid_interactions": 0,
        "new_users": 0,
        "new_products": 0,
        "archived_files": 0
    }
    
    logger.info("Bat đau job cap nhat batch...")

    # Kiem tra cau hinh Azure
    if not USE_AZURE_STORAGE:
        logger.error("USE_AZURE_STORAGE khong đuoc bat, dung job.")
        return
        
    if not AZURE_CONNECTION_STRING:
        logger.error("AZURE_CONNECTION_STRING khong đuoc cau hinh")
        return

    # Khoi tao Cosmos DB
    cosmos_init_start = time.time()
    cosmos_container = initialize_cosmos_db()
    logger.info(f"Khoi tao Cosmos DB mat {time.time() - cosmos_init_start:.2f} giay")
    
    if not cosmos_container:
        logger.error("Khong the khoi tao Cosmos DB, dung job.")
        return

    try:
        # Khoi tao Azure Blob Storage client
        blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
        blob_container_client = blob_service_client.get_container_client(AZURE_CONTAINER)

        # Kiem tra xem co files moi khong
        blob_check_start = time.time()
        blob_list = list(blob_container_client.list_blobs(name_starts_with=NEW_DATA_PATH))
        logger.info(f"Kiem tra file moi mat {time.time() - blob_check_start:.2f} giay, tim thay {len(blob_list)} files")
        
        if not blob_list:
            logger.info("Khong co file tuong tac moi, ket thuc job")
            return

        # Tao thu muc tam đe luu artifacts
        with tempfile.TemporaryDirectory() as temp_dir:
            # Tai artifacts hien co tu Azure (Model tu Cosmos DB, encoders va matrix tu Blob)
            load_artifacts_start = time.time()
            artifacts = load_existing_artifacts(blob_service_client, AZURE_CONTAINER, cosmos_container, temp_dir)
            logger.info(f"Tai artifacts hien co mat {time.time() - load_artifacts_start:.2f} giay")
            
            # Luu lai so luong users va products truoc khi cap nhat
            user_count_before = len(artifacts['user_encoder'].classes_) if hasattr(artifacts['user_encoder'], 'classes_') else 0
            product_count_before = len(artifacts['product_encoder'].classes_) if hasattr(artifacts['product_encoder'], 'classes_') else 0
            
            # Tai du lieu tuong tac moi tu Blob
            load_interactions_start = time.time()
            interactions_df = load_interaction_files(blob_container_client, NEW_DATA_PATH)
            load_interactions_time = time.time() - load_interactions_start
            
            # Cap nhat metrics
            job_metrics["files_processed"] = len(blob_list)
            job_metrics["interactions_processed"] = len(interactions_df) if not interactions_df.empty else 0
            
            if interactions_df.empty:
                logger.warning("Khong co du lieu tuong tac hop le sau khi kiem tra")
                return
                
            logger.info(f"Tai va xu ly du lieu tuong tac moi mat {load_interactions_time:.2f} giay, {len(interactions_df)} tuong tac")

            # Cap nhat artifacts voi du lieu moi va huan luyen lai model
            update_start = time.time()
            updated_artifacts = update_artifacts_with_new_data(artifacts, interactions_df)
            update_time = time.time() - update_start
            
            # Tinh so users va products moi đuoc them vao
            user_count_after = len(updated_artifacts['user_encoder'].classes_) if hasattr(updated_artifacts['user_encoder'], 'classes_') else 0
            product_count_after = len(updated_artifacts['product_encoder'].classes_) if hasattr(updated_artifacts['product_encoder'], 'classes_') else 0
            
            job_metrics["new_users"] = user_count_after - user_count_before
            job_metrics["new_products"] = product_count_after - product_count_before
            
            logger.info(f"Cap nhat artifacts va huan luyen lai model mat {update_time:.2f} giay")
            logger.info(f"Đa them {job_metrics['new_users']} users moi va {job_metrics['new_products']} products moi")

            # Luu artifacts đa cap nhat (Model vao Cosmos DB, con lai vao Blob)
            save_start = time.time()
            success = save_artifacts(
                updated_artifacts,
                blob_service_client,
                AZURE_CONTAINER,
                cosmos_container,
                temp_dir
            )
            logger.info(f"Luu artifacts đa cap nhat mat {time.time() - save_start:.2f} giay")

            if success:
                # Luu tru cac files đa xu ly trong Blob
                archive_start = time.time()
                archived_count = archive_processed_files(blob_container_client, NEW_DATA_PATH, ARCHIVE_PATH)
                job_metrics["archived_files"] = archived_count
                logger.info(f"Luu tru {archived_count} files đa xu ly mat {time.time() - archive_start:.2f} giay")
                
                total_time = time.time() - start_time
                logger.info(f"Job cap nhat hoan tat thanh cong trong {total_time:.2f} giay")
                
                # Log metrics tong hop
                logger.info(f"Thong ke job: {job_metrics}")
            else:
                logger.error("Khong the luu artifacts, huy job")

    except Exception as e:
        logger.error(f"Loi khi chay job cap nhat batch: {e}")
        import traceback
        logger.error(traceback.format_exc())

if __name__ == "__main__":
    run_batch_update() 