import base64
import io
import math
import os
import uuid
import time
from datetime import datetime, timezone
from azure.cosmos import CosmosClient, PartitionKey
from dotenv import load_dotenv

load_dotenv()

# Cau hinh thong tin Cosmos DB
COSMOS_ENDPOINT = os.getenv('COSMOS_ENDPOINT', "")
COSMOS_KEY = os.getenv('COSMOS_KEY')
COSMOS_DATABASE_NAME = os.getenv('COSMOS_DATABASE_NAME', "")
COSMOS_CONTAINER_NAME = os.getenv('COSMOS_CONTAINER_NAME', "")

# Blob Storage encoders/matrix info
AZURE_USER_ENCODER_BLOB_NAME = os.getenv('AZURE_USER_ENCODER_BLOB_NAME', 'user_encoder.pkl')
AZURE_PRODUCT_ENCODER_BLOB_NAME = os.getenv('AZURE_PRODUCT_ENCODER_BLOB_NAME', 'product_encoder.pkl')
AZURE_INTERACTION_MATRIX_BLOB_NAME = os.getenv('AZURE_INTERACTION_MATRIX_BLOB_NAME', 'interaction_matrix.npz')

# Max chunk size - giam xuong de dam bao sau khi ma hoa base64 van duoi 2MB
MAX_CHUNK_SIZE_BYTES = 750 * 1024  # 750KB

try:
    # === BUOC 1: CHUAN BI METADATA VA KET NOI ===
    print("Bat dau qua trinh upload model...")
    
    # Tao model ID va partition key
    model_id_suffix = uuid.uuid4().hex[:8]
    timestamp_str = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S')
    model_chunk_partition_key = f"alsmodel_chunks_{timestamp_str}_{model_id_suffix}"
    descriptor_id = f"desc_{timestamp_str}_{model_id_suffix}"
    DESCRIPTOR_PARTITION_KEY_VALUE = "als_model_descriptor"
    
    # Thong tin ve tham so training cua model
    FACTORS = int(os.getenv('ALS_FACTORS', '10'))
    REGULARIZATION = float(os.getenv('ALS_REGULARIZATION', '0.01'))
    ITERATIONS = int(os.getenv('ALS_ITERATIONS', '2'))
    ALPHA = float(os.getenv('ALS_ALPHA', '1.0'))
    
    print(f"Model ID: {model_id_suffix}")
    print(f"Model chunk partition key: {model_chunk_partition_key}")
    
    # === BUOC 2: DOC FILE MODEL ===
    model_path = './artifacts/alsmodel.pkl'
    if not os.path.exists(model_path):
        raise FileNotFoundError(f"Khong tim thay file model tai duong dan {model_path}")
        
    # Lay kich thuoc file
    model_size = os.path.getsize(model_path)
    print(f"Kich thuoc model: {model_size} bytes")
    
    # === BUOC 3: KET NOI TOI COSMOS DB ===
    if not COSMOS_KEY:
        raise ValueError("COSMOS_KEY khong duoc cau hinh trong bien moi truong")
    
    print(f"Ket noi toi Cosmos DB: {COSMOS_ENDPOINT}")
    print(f"Database: {COSMOS_DATABASE_NAME}, Container: {COSMOS_CONTAINER_NAME}")
    
    client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
    database = client.get_database_client(COSMOS_DATABASE_NAME)
    container = database.get_container_client(COSMOS_CONTAINER_NAME)
    
    # === BUOC 4: CHIA NHO VA LUU TUNG PHAN LEN COSMOS DB ===
    chunk_index = 0
    total_chunks = math.ceil(model_size / MAX_CHUNK_SIZE_BYTES)
    print(f"Du kien chia model thanh {total_chunks} chunks")
    
    # Doc va xu ly file theo tung doan de tranh tai toan bo vao bo nho
    with open(model_path, 'rb') as file:
        while True:
            chunk_data_bytes = file.read(MAX_CHUNK_SIZE_BYTES)
            if not chunk_data_bytes:
                break
                
            encoded_chunk_data_str = base64.b64encode(chunk_data_bytes).decode('utf-8')
            encoded_size = len(encoded_chunk_data_str)
            
            chunk_doc_id = f"chunk_{model_chunk_partition_key}_{chunk_index}"
            chunk_doc = {
                'id': chunk_doc_id,
                'partition_key': model_chunk_partition_key,
                'documentType': 'modelChunk',
                'chunk_index': chunk_index,
                'total_chunks': total_chunks,  # Luu truoc total_chunks
                'file_chunk': encoded_chunk_data_str,
                'timestamp': datetime.now(timezone.utc).isoformat()
            }
            
            # Tai len Cosmos DB voi retry
            max_retries = 3
            retry_count = 0
            upload_success = False
            
            while not upload_success and retry_count < max_retries:
                try:
                    container.upsert_item(chunk_doc)
                    print(f"Da luu model chunk {chunk_index}/{total_chunks} voi id {chunk_doc_id} (kich thuoc sau encode: {encoded_size} bytes)")
                    upload_success = True
                except Exception as e:
                    retry_count += 1
                    if retry_count < max_retries:
                        wait_time = 2 ** retry_count  # Exponential backoff
                        print(f"Loi khi luu chunk {chunk_index}: {e}. Thu lai sau {wait_time}s (lan {retry_count}/{max_retries})")
                        time.sleep(wait_time)
                    else:
                        print(f"Khong the luu chunk {chunk_index} sau {max_retries} lan thu: {e}")
                        raise
            
            chunk_index += 1
    
    # Double-check so luong chunks thuc te
    if chunk_index != total_chunks:
        print(f"Canh bao: So luong chunks du kien ({total_chunks}) khac voi so luong thuc te ({chunk_index})")
        total_chunks = chunk_index
    
    # === BUOC 5: TAO VA LUU MODEL DESCRIPTOR ===
    model_descriptor_doc = {
        'id': descriptor_id,
        'partition_key': DESCRIPTOR_PARTITION_KEY_VALUE,
        'documentType': 'modelDescriptor',
        'timestamp': datetime.now(timezone.utc).isoformat(),
        'modelChunkPartitionKey': model_chunk_partition_key,
        'userEncoderBlobName': AZURE_USER_ENCODER_BLOB_NAME,
        'productEncoderBlobName': AZURE_PRODUCT_ENCODER_BLOB_NAME,
        'interactionMatrixBlobName': AZURE_INTERACTION_MATRIX_BLOB_NAME,
        'modelSizeBytes': model_size,
        'modelTotalChunks': total_chunks,
        'status': 'active',
        'training_params': {
            'factors': FACTORS,
            'regularization': REGULARIZATION,
            'iterations': ITERATIONS,
            'alpha': ALPHA
        }
    }
    
    # Luu model descriptor
    try:
        container.upsert_item(model_descriptor_doc)
        print(f"Da luu model descriptor voi id: {descriptor_id}")
        print("----------------- KET QUA -----------------")
        print(f"Upload thanh cong {total_chunks} phan cua alsmodel.pkl va descriptor len Azure Cosmos DB.")
        print(f"Model Descriptor ID: {descriptor_id}")
        print(f"Model Chunk Partition Key: {model_chunk_partition_key}")
    except Exception as e:
        print(f"Loi khi luu model descriptor: {e}")
        raise

except Exception as e:
    print(f"Loi trong qua trinh upload model: {e}")
    import traceback
    traceback.print_exc()

