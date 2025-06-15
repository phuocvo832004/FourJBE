print("DEBUG: app/main.py execution started - BEGINNING OF FILE")
# services/recommendation-service/app/main.py
import pickle
import os
import socket
import time
import tempfile
from pathlib import Path
import numpy as np
from scipy.sparse import load_npz, csr_matrix
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from implicit.als import AlternatingLeastSquares # Import class model của bạn
from sklearn.preprocessing import LabelEncoder
from azure.storage.blob import BlobServiceClient
from azure.cosmos import CosmosClient # Thêm Cosmos Client
import base64 # Để decode nếu cần
from dotenv import load_dotenv

load_dotenv()
# --- Cấu hình ---
ARTIFACTS_DIR = Path(__file__).parent.parent / "artifacts" 
USER_ENCODER_PATH = ARTIFACTS_DIR / "user_encoder.pkl"
PRODUCT_ENCODER_PATH = ARTIFACTS_DIR / "product_encoder.pkl"
INTERACTION_MATRIX_PATH = ARTIFACTS_DIR / "interaction_matrix.npz"
RECOMMENDATION_COUNT = 20 
MINIMUM_RECOMMENDATION_COUNT = 15
FALLBACK_POPULAR_PRODUCTS_COUNT = 50 

# --- Cấu hình Azure Blob Storage ---
AZURE_CONNECTION_STRING = os.getenv('AZURE_CONNECTION_STRING', '')
AZURE_CONTAINER = os.getenv('AZURE_CONTAINER', 'orderhistory')
USE_AZURE_STORAGE = os.getenv('USE_AZURE_STORAGE', 'false').lower() == 'true'
AZURE_USER_ENCODER_BLOB_NAME = os.getenv('AZURE_USER_ENCODER_BLOB_NAME', 'user_encoder.pkl')
AZURE_PRODUCT_ENCODER_BLOB_NAME = os.getenv('AZURE_PRODUCT_ENCODER_BLOB_NAME', 'product_encoder.pkl')
AZURE_INTERACTION_MATRIX_BLOB_NAME = os.getenv('AZURE_INTERACTION_MATRIX_BLOB_NAME', 'interaction_matrix.npz')

# --- Cấu hình Azure Cosmos DB (Thêm vào) ---
COSMOS_ENDPOINT = os.getenv('COSMOS_ENDPOINT')
COSMOS_KEY = os.getenv('COSMOS_KEY')
COSMOS_DATABASE_NAME = os.getenv('COSMOS_DATABASE_NAME', 'RecommendationDB')
COSMOS_CONTAINER_NAME = os.getenv('COSMOS_MODELS_CONTAINER_NAME', 'Models')

# --- Cấu hình Service ---
SERVICE_NAME = "recommendation-service"
SERVICE_ID = f"{SERVICE_NAME}-{socket.gethostname()}"
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "8090"))

# --- Biến toàn cục để giữ model và dữ liệu đã load ---
app_state = {}

# --- Khởi tạo ứng dụng FastAPI ---
app = FastAPI(
    title="Recommendation Service",
    description="API for getting product recommendations based on user purchase history.",
    version="0.1.0"
)

# Thêm CORS middleware
""" Comment out CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Trong môi trường production, chỉ định cụ thể origins
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
"""

# --- Hàm để tải artifact từ Azure Blob Storage ---
def download_blob_to_file(blob_client, file_path):
    """
    Tải một blob từ Azure Blob Storage về tệp cục bộ
    """
    try:
        with open(file_path, "wb") as file:
            data = blob_client.download_blob()
            file.write(data.readall())
        return True
    except Exception as e:
        print(f"Error downloading blob: {e}")
        return False

# --- Hàm tính toán sản phẩm phổ biến từ ma trận tương tác ---
def compute_popular_products(interaction_matrix, product_encoder):
    """
    Tính toán các sản phẩm phổ biến nhất dựa trên ma trận tương tác.
    
    Args:
        interaction_matrix: Ma trận tương tác sparse (scipy.sparse matrix)
        product_encoder: Label encoder cho product_id
        
    Returns:
        list: Danh sách các product_id phổ biến nhất
    """
    print("Computing popular products for fallback recommendations...")
    
    # Đảm bảo ma trận ở dạng CSC để tính tổng theo cột hiệu quả
    if not isinstance(interaction_matrix, csr_matrix):
        interaction_matrix = interaction_matrix.tocsr()
    
    # Tính tổng tương tác cho mỗi sản phẩm (tổng theo cột)
    popularity_scores = np.array(interaction_matrix.sum(axis=0)).flatten()  # Lấy tổng số lượng tương tác
    
    # Lấy các chỉ số của sản phẩm theo thứ tự giảm dần của điểm phổ biến
    popular_indices = np.argsort(popularity_scores)[::-1][:FALLBACK_POPULAR_PRODUCTS_COUNT]
    
    # Chuyển đổi từ product_idx sang product_id thực tế
    popular_product_ids = product_encoder.inverse_transform(popular_indices)
    
    print(f"Identified {len(popular_product_ids)} popular products for fallback recommendations")
    return [int(pid) for pid in popular_product_ids]

def load_latest_model_and_artifacts_from_azure():
    """
    Tải model mới nhất từ Cosmos DB (sử dụng model descriptor) và các artifacts liên quan từ Azure Blob Storage.
    """
    print(f"Loading artifacts: Model from Cosmos DB ({COSMOS_DATABASE_NAME}/{COSMOS_CONTAINER_NAME}), others from Blob ({AZURE_CONTAINER})")

    if not AZURE_CONNECTION_STRING or not COSMOS_ENDPOINT or not COSMOS_KEY:
        print("Error: Azure Storage or Cosmos DB connection details missing.")
        return False

    temp_dir = tempfile.mkdtemp()
    print(f"Created temporary directory for downloads: {temp_dir}")
    model = None
    user_encoder_blob_name = None # Sẽ được lấy từ descriptor
    product_encoder_blob_name = None # Sẽ được lấy từ descriptor
    interaction_matrix_blob_name = None # Sẽ được lấy từ descriptor

    try:
        print("Attempting to connect to Cosmos DB...")
        cosmos_client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
        database = cosmos_client.get_database_client(COSMOS_DATABASE_NAME)
        container = database.get_container_client(COSMOS_CONTAINER_NAME)
        print(f"Successfully connected to Cosmos DB: Database='{COSMOS_DATABASE_NAME}', Container='{COSMOS_CONTAINER_NAME}'")

        # 1. Truy vấn modelDescriptor mới nhất và active
        DESCRIPTOR_PARTITION_KEY_VALUE = "als_model_descriptor"
        descriptor_query = (
            "SELECT TOP 1 * FROM c "
            "WHERE c.documentType = 'modelDescriptor' AND c.partition_key = @desc_pk AND c.status = 'active' "
            "ORDER BY c.timestamp DESC"
        )
        descriptor_query_params = [dict(name="@desc_pk", value=DESCRIPTOR_PARTITION_KEY_VALUE)]
        
        print(f"Executing query to find latest active model descriptor with partition_key '{DESCRIPTOR_PARTITION_KEY_VALUE}'...")
        model_descriptors = list(container.query_items(
            query=descriptor_query, 
            parameters=descriptor_query_params, 
            enable_cross_partition_query=True
        ))

        if not model_descriptors:
            print("Error: No active model descriptor found in Cosmos DB.")
            # Fallback: Có thể thử tìm model descriptor mới nhất bất kể trạng thái, hoặc một model mặc định.
            # print("Attempting to find any latest model descriptor...")
            # descriptor_query_any = "SELECT TOP 1 * FROM c WHERE c.documentType = 'modelDescriptor' AND c.partition_key = @desc_pk ORDER BY c.timestamp DESC"
            # model_descriptors = list(container.query_items(query=descriptor_query_any, parameters=descriptor_query_params, enable_cross_partition_query=False))
            # if not model_descriptors:
            #    print("Error: No model descriptor (active or inactive) found in Cosmos DB.")
            #    return False
            # print("Found a model descriptor, but it might not be active. Proceeding with caution.")
            return False # Strict: Chỉ load model active

        model_descriptor_doc = model_descriptors[0]
        print(f"Found latest active model descriptor: ID={model_descriptor_doc.get('id')}, Timestamp={model_descriptor_doc.get('timestamp')}")

        model_chunk_partition_key = model_descriptor_doc.get('modelChunkPartitionKey')
        if not model_chunk_partition_key:
            print(f"Error: Model descriptor ID '{model_descriptor_doc.get('id')}' is missing 'modelChunkPartitionKey'. Cannot load model chunks.")
            return False

        # Lấy tên blob artifacts từ descriptor, fallback về biến môi trường nếu thiếu
        user_encoder_blob_name = model_descriptor_doc.get('userEncoderBlobName', AZURE_USER_ENCODER_BLOB_NAME)
        product_encoder_blob_name = model_descriptor_doc.get('productEncoderBlobName', AZURE_PRODUCT_ENCODER_BLOB_NAME)
        interaction_matrix_blob_name = model_descriptor_doc.get('interactionMatrixBlobName', AZURE_INTERACTION_MATRIX_BLOB_NAME)

        print(f"Attempting to load model chunks using modelChunkPartitionKey: '{model_chunk_partition_key}'")
        chunk_query = "SELECT * FROM c WHERE c.partition_key = @chunk_pk AND c.documentType = 'modelChunk' ORDER BY c.chunk_index ASC"
        chunk_query_params = [dict(name="@chunk_pk", value=model_chunk_partition_key)]
        
        model_chunk_docs = list(container.query_items(
            query=chunk_query, 
            parameters=chunk_query_params, 
            enable_cross_partition_query=True
        ))

        if not model_chunk_docs:
            print(f"Error: No model chunks found for modelChunkPartitionKey '{model_chunk_partition_key}'. Ensure model chunks were uploaded correctly.")
            return False
        
        print(f"Retrieved {len(model_chunk_docs)} model chunks for modelChunkPartitionKey '{model_chunk_partition_key}'.")

        # Optional: Verify completeness
        expected_total_chunks = model_descriptor_doc.get('modelTotalChunks', len(model_chunk_docs)) # Lấy từ descriptor hoặc số lượng thực tế
        if len(model_chunk_docs) != expected_total_chunks:
            print(f"Warning: Expected {expected_total_chunks} chunks (from descriptor or actual count), but retrieved {len(model_chunk_docs)}. The model might be incomplete or descriptor outdated.")
        else:
            print(f"Successfully retrieved all {expected_total_chunks} expected model chunks.")

        # Assemble the model from chunks
        try:
            # Sort by chunk_index explicitly to ensure proper order
            model_chunk_docs.sort(key=lambda doc: doc['chunk_index'])

            # +++ BEGIN DIAGNOSTIC LOGGING +++
            print(f"DEBUG: Total model chunks retrieved: {len(model_chunk_docs)}")
            
            # Verify chunk continuity - check for missing chunks
            expected_chunk_indexes = set(range(len(model_chunk_docs)))
            actual_chunk_indexes = set(doc.get('chunk_index') for doc in model_chunk_docs)
            missing_indexes = expected_chunk_indexes - actual_chunk_indexes
            
            if missing_indexes:
                print(f"ERROR: Missing {len(missing_indexes)} chunks: {sorted(missing_indexes)}")
                return False
                
            all_chunk_data_strings = [] # Changed variable name for clarity
            for i, chunk_doc in enumerate(model_chunk_docs):
                chunk_index_val = chunk_doc.get('chunk_index', 'N/A')
                expected_index = i  # Chunks should be in order 0, 1, 2, ...
                
                if chunk_index_val != expected_index:
                    print(f"WARNING: Chunk at position {i} has unexpected index {chunk_index_val}, expected {expected_index}")
                
                file_chunk_content = chunk_doc.get('file_chunk', '')
                print(f"DEBUG: Chunk {i}: Index from doc: {chunk_index_val}, file_chunk length: {len(file_chunk_content)}")
                if not file_chunk_content:
                    print(f"WARNING: Chunk {i} (Index {chunk_index_val}) has empty file_chunk!")
                all_chunk_data_strings.append(file_chunk_content)
            # +++ END DIAGNOSTIC LOGGING +++

            # NEW WAY: Decode each chunk's base64 string individually, then concatenate the resulting bytes.
            all_decoded_byte_chunks = []
            print("DEBUG: Starting individual chunk decoding...")
            for i, encoded_chunk_str in enumerate(all_chunk_data_strings):
                try:
                    # Ensure the encoded string is suitable for b64decode (e.g., encode to ascii if it's a pure Python string)
                    # However, file_chunk_content should already be a string from JSON parsing.
                    decoded_bytes_for_chunk = base64.b64decode(encoded_chunk_str)
                    all_decoded_byte_chunks.append(decoded_bytes_for_chunk)
                    print(f"DEBUG: Chunk {i} successfully decoded. Encoded length: {len(encoded_chunk_str)}, Decoded length: {len(decoded_bytes_for_chunk)}")
                except Exception as e_decode:
                    print(f"ERROR: Failed to decode base64 for chunk {i}. Length: {len(encoded_chunk_str)}. Error: {e_decode}")
                    # If any chunk fails to decode, the model is corrupt.
                    return False

            model_bytes = b"".join(all_decoded_byte_chunks)
            print(f"DEBUG: Total length of concatenated DECODED bytes: {len(model_bytes)}")

            expected_model_size_bytes = model_descriptor_doc.get('modelSizeBytes')
            actual_model_size_bytes = len(model_bytes) # This is the critical value now
            print(f"DEBUG: Expected model size (from descriptor): {expected_model_size_bytes} bytes.")
            print(f"DEBUG: Actual final decoded model size (from concatenated bytes): {actual_model_size_bytes} bytes.")

            if expected_model_size_bytes is not None and actual_model_size_bytes != expected_model_size_bytes:
                print(f"CRITICAL MISMATCH: Model size does not match! Expected {expected_model_size_bytes}, Got {actual_model_size_bytes}. Data may be lost or corrupted.")
                # Return False if sizes don't match significantly (allow small differences due to potential padding)
                if abs(expected_model_size_bytes - actual_model_size_bytes) > 100:
                    print("Size mismatch is significant. Aborting model loading.")
                    return False
            
            # Check if model_bytes is empty before attempting to load
            if not model_bytes:
                print("ERROR: Decoded model_bytes is empty. Cannot proceed with pickle.loads.")
                return False

            try:
                model = pickle.loads(model_bytes)
                print(f"Model deserialized successfully from {len(model_chunk_docs)} chunks.")
                app_state["model"] = model
            except pickle.UnpicklingError as unpickle_err:
                print(f"ERROR: Failed to unpickle model data: {unpickle_err}. This often indicates corrupted or incomplete data.")
                return False
        except KeyError as ke:
            print(f"Error assembling chunks: 'file_chunk' or 'chunk_index' missing. {ke}")
            return False
        except Exception as e:
            print(f"Failed to decode/deserialize assembled model data: {e}")
            return False

        # 2. Kết nối Blob Storage và tải các artifacts khác
        print(f"Connecting to Azure Blob Storage: container={AZURE_CONTAINER}")
        blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
        blob_container_client = blob_service_client.get_container_client(AZURE_CONTAINER)
        print("Successfully connected to Blob Storage")

        artifacts_loaded_successfully = True
        # Tải User Encoder
        if user_encoder_blob_name:
            user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
            print(f"Downloading user encoder from blob: {user_encoder_blob_name}")
            user_encoder_blob = blob_container_client.get_blob_client(user_encoder_blob_name)
            if user_encoder_blob.exists() and download_blob_to_file(user_encoder_blob, user_encoder_path):
                with open(user_encoder_path, 'rb') as f:
                    app_state["user_encoder"] = pickle.load(f)
                print(f"User encoder loaded: {len(app_state['user_encoder'].classes_)} classes")
            else:
                print(f"Failed to load user encoder from {user_encoder_blob_name}")
                artifacts_loaded_successfully = False
        else:
            print("User encoder blob name not specified or found in descriptor.")
            artifacts_loaded_successfully = False

        # Tải Product Encoder
        if product_encoder_blob_name:
            product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
            print(f"Downloading product encoder from blob: {product_encoder_blob_name}")
            product_encoder_blob = blob_container_client.get_blob_client(product_encoder_blob_name)
            if product_encoder_blob.exists() and download_blob_to_file(product_encoder_blob, product_encoder_path):
                with open(product_encoder_path, 'rb') as f:
                    app_state["product_encoder"] = pickle.load(f)
                print(f"Product encoder loaded: {len(app_state['product_encoder'].classes_)} classes")
            else:
                print(f"Failed to load product encoder from {product_encoder_blob_name}")
                artifacts_loaded_successfully = False
        else:
            print("Product encoder blob name not specified or found in descriptor.")
            artifacts_loaded_successfully = False

        # Tải Interaction Matrix
        if interaction_matrix_blob_name:
            interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"
            print(f"Downloading interaction matrix from blob: {interaction_matrix_blob_name}")
            interaction_matrix_blob = blob_container_client.get_blob_client(interaction_matrix_blob_name)
            if interaction_matrix_blob.exists() and download_blob_to_file(interaction_matrix_blob, interaction_matrix_path):
                app_state["interaction_matrix"] = load_npz(interaction_matrix_path)
                print(f"Interaction matrix loaded, shape: {app_state['interaction_matrix'].shape}")
            else:
                print(f"Failed to load interaction matrix from {interaction_matrix_blob_name}")
                artifacts_loaded_successfully = False
        else:
            print("Interaction matrix blob name not specified or found in descriptor.")
            artifacts_loaded_successfully = False

        if artifacts_loaded_successfully:
            user_encoder = app_state.get("user_encoder")
            product_encoder = app_state.get("product_encoder")
            interaction_matrix = app_state.get("interaction_matrix")
            if user_encoder and product_encoder and interaction_matrix is not None:
                 num_products_encoder = len(product_encoder.classes_)
                 num_users_matrix, num_products_matrix = interaction_matrix.shape
                 if num_products_encoder != num_products_matrix:
                      print(f"CRITICAL WARNING: Mismatch! Encoder Products={num_products_encoder}, Matrix Columns={num_products_matrix}")
                 else:
                      print("Product count consistency check passed.")
                 app_state["popular_products"] = compute_popular_products(interaction_matrix, product_encoder)
            else:
                 print("Warning: Some artifacts (encoders/matrix) were not loaded correctly for consistency check or popular products computation.")
                 return False # Critical if encoders/matrix are missing
            print("All artifacts loaded and model ready.")
            return True
        else:
            print("One or more artifacts failed to load. Service may not be fully operational.")
            return False

    except Exception as e:
        print(f"Unexpected error loading artifacts from Azure: {str(e)}")
        import traceback
        traceback.print_exc()
        return False
    finally:
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)
        print(f"Cleaned up temporary directory: {temp_dir}")

# --- Sự kiện Startup: Load model và các artifacts ---
@app.on_event("startup")
async def startup_events():
    # Load artifacts
    loaded_successfully = False
    if USE_AZURE_STORAGE and AZURE_CONNECTION_STRING and COSMOS_ENDPOINT and COSMOS_KEY:
        print("*" * 50)
        print("DEBUG: Azure configuration:")
        print(f"USE_AZURE_STORAGE: {USE_AZURE_STORAGE}")
        print(f"AZURE_CONNECTION_STRING: {'Provided (value hidden)' if AZURE_CONNECTION_STRING else 'NOT PROVIDED'}")
        print(f"AZURE_CONTAINER: {AZURE_CONTAINER}")
        print(f"COSMOS_ENDPOINT: {'Provided (value hidden)' if COSMOS_ENDPOINT else 'NOT PROVIDED'}")
        print(f"COSMOS_DATABASE_NAME: {COSMOS_DATABASE_NAME}")
        print(f"COSMOS_CONTAINER_NAME: {COSMOS_CONTAINER_NAME}")
        print("*" * 50)
        print("Attempting to load artifacts from Azure (Cosmos DB + Blob Storage)")
        loaded_successfully = load_latest_model_and_artifacts_from_azure()
        if not loaded_successfully:
            print("Failed to load artifacts from Azure. Check logs for errors. Service might not function correctly.")
            # Không fallback về local - như yêu cầu
    else:
        print("*" * 50)
        print("DEBUG: Azure configuration is incomplete:")
        print(f"USE_AZURE_STORAGE: {USE_AZURE_STORAGE}")
        print(f"AZURE_CONNECTION_STRING provided: {'Yes' if AZURE_CONNECTION_STRING else 'No'}")
        print(f"COSMOS_ENDPOINT provided: {'Yes' if COSMOS_ENDPOINT else 'No'}")
        print(f"COSMOS_KEY provided: {'Yes' if COSMOS_KEY else 'No'}")
        print("*" * 50)
        print("Azure storage/Cosmos DB not configured properly. Service will not function correctly.")
        # Không load từ local - như yêu cầu

    if not loaded_successfully:
         print("CRITICAL: Model and artifacts could not be loaded. API endpoints will fail with 503 error.")

def load_artifacts_from_local():
    """
    Tải artifacts từ thư mục cục bộ
    """
    print(f"Loading artifacts from: {ARTIFACTS_DIR}")
    if not ARTIFACTS_DIR.exists():
        print(f"Error: Artifacts directory not found at {ARTIFACTS_DIR}")
        # Có thể raise lỗi ở đây hoặc xử lý khác tùy theo yêu cầu
        return

    try:
        with open(USER_ENCODER_PATH, 'rb') as f:
            app_state["user_encoder"] = pickle.load(f)
            num_users_encoder = len(app_state["user_encoder"].classes_)
            print(f"User encoder loaded from {USER_ENCODER_PATH} ({num_users_encoder} users)")

        with open(PRODUCT_ENCODER_PATH, 'rb') as f:
            app_state["product_encoder"] = pickle.load(f)
            num_products_encoder = len(app_state["product_encoder"].classes_)
            print(f"Product encoder loaded from {PRODUCT_ENCODER_PATH} ({num_products_encoder} products)")

        app_state["interaction_matrix"] = load_npz(INTERACTION_MATRIX_PATH)
        num_users_matrix, num_products_matrix = app_state["interaction_matrix"].shape
        print(f"Interaction matrix loaded from {INTERACTION_MATRIX_PATH} (Shape: {num_users_matrix}x{num_products_matrix})")

        # --- Thêm kiểm tra sự nhất quán ---
        if num_products_encoder != num_products_matrix:
            print(f"CRITICAL WARNING: Mismatch in product counts! Encoder knows {num_products_encoder}, Matrix has {num_products_matrix} columns.")
            # Có thể raise lỗi hoặc đặt một cờ báo lỗi ở đây nếu muốn dừng service
        else:
            print("Product count consistency check passed.")
        # --- Kết thúc kiểm tra ---
        
        # Tính toán các sản phẩm phổ biến để dùng làm fallback
        app_state["popular_products"] = compute_popular_products(app_state["interaction_matrix"], app_state["product_encoder"])
        
        # Kiểm tra xem model có phải là instance của class mong đợi không
        if not isinstance(app_state.get("model"), AlternatingLeastSquares):
             print("Warning: Loaded model might not be the expected type.")

        print("Artifacts loaded successfully.")
    except FileNotFoundError as e:
        print(f"Error loading artifact: {e}. Make sure all artifact files exist.")
        # Xử lý lỗi nghiêm trọng hơn nếu cần
    except Exception as e:
        print(f"An unexpected error occurred during artifact loading: {e}")
        # Xử lý lỗi

# --- Sự kiện Shutdown ---
@app.on_event("shutdown")
async def shutdown_events():
    print("Service shutting down")

# --- API Endpoint ---
@app.get("/api/v1/recommendations/{user_id}",
         summary="Get Product Recommendations for a User",
         response_description="A list of recommended product IDs")
async def get_recommendations(user_id: int):
    """
    Retrieves product recommendations for a given `user_id`.

    - **user_id**: The ID of the user to get recommendations for.
    """
    # Lấy các artifacts từ app_state một cách an toàn
    model = app_state.get("model")
    user_encoder = app_state.get("user_encoder")
    product_encoder = app_state.get("product_encoder")
    interaction_matrix = app_state.get("interaction_matrix")
    popular_products = app_state.get("popular_products", [])

    # Kiểm tra xem tất cả các artifacts cần thiết đã được load chưa
    if (model is None or
        user_encoder is None or
        product_encoder is None or
        interaction_matrix is None):
        # Ghi log lỗi nếu cần thiết để debug
        print("Error: One or more artifacts failed to load or are missing.")
        raise HTTPException(status_code=503, detail="Service Unavailable: Required artifacts not loaded.")
        
    print(f"\n--- Request for user_id: {user_id} ---") # Thêm log bắt đầu request

    # Chuyển đổi user_id sang string để khớp với kiểu dữ liệu trong encoder
    user_id_str = str(user_id)
    print(f"Converted input user_id to string: '{user_id_str}' for lookup")

    try:
        model_recommendations = []
        
        # Kiểm tra xem user_id_str có trong encoder không  
        if user_id_str in user_encoder.classes_:
            # 1. Chuyển user_id_str thành user_idx
            print(f"Looking up user_id_str: '{user_id_str}'")
            user_idx = user_encoder.transform([user_id_str])[0]
            print(f"Found user_idx: {user_idx}") # Thêm log user_idx

            # 2. Lấy vector tương tác của user từ sparse matrix
            # Đảm bảo interaction_matrix là CSR để truy cập hàng hiệu quả
            if not isinstance(interaction_matrix, csr_matrix):
                 interaction_matrix = interaction_matrix.tocsr() # Chuyển đổi nếu cần
            user_items_sparse = interaction_matrix[user_idx]
            print(f"User items vector shape: {user_items_sparse.shape}, Non-zero elements: {user_items_sparse.nnz}") # Thêm log vector

            # 3. Gọi hàm recommend
            print("Calling model.recommend...")
            
            # Model đã được huấn luyện với ma trận tương tác thông thường (không chuyển vị)
            # Tiếp tục sử dụng user_items_sparse trực tiếp
            
            # Debug thông tin về model
            print(f"DEBUG: Model object type: {type(model)}")
            print(f"DEBUG: Model factors: {model.factors}, Model regularization: {model.regularization}")
            
            # Giả sử model ALS có hai ma trận yếu tố: item_factors và user_factors
            if hasattr(model, 'item_factors') and hasattr(model, 'user_factors'):
                print(f"DEBUG: Model contains factor matrices. user_factors shape: {model.user_factors.shape if model.user_factors is not None else 'None'}, item_factors shape: {model.item_factors.shape if model.item_factors is not None else 'None'}")

            ids, scores = model.recommend(
                userid=user_idx,
                user_items=user_items_sparse,
                N=RECOMMENDATION_COUNT,
                filter_already_liked_items=False
            )
            
            print(f"Raw recommendations (item_idx, score): {ids}, {scores}")

            recommended_product_idxs_raw = [int(idx) for idx in ids]
            print(f"Raw recommended product_idxs: {recommended_product_idxs_raw}")

            # --- Bước lọc index không hợp lệ ---
            valid_indices_mask = np.isin(recommended_product_idxs_raw, np.arange(len(product_encoder.classes_)))
            recommended_product_idxs = np.array(recommended_product_idxs_raw)[valid_indices_mask].tolist()

            if len(recommended_product_idxs) < len(recommended_product_idxs_raw):
                print(f"Filtered out invalid product_idxs. Kept: {recommended_product_idxs}")
            # --- Kết thúc bước lọc ---

            if not recommended_product_idxs:
                 print("No valid product indices recommended after filtering.")
            else:     
                # Gọi inverse_transform với danh sách index đã được lọc và hợp lệ
                model_recommendations = [int(pid) for pid in product_encoder.inverse_transform(recommended_product_idxs)]
                print(f"Decoded product_ids from model: {model_recommendations}")
        else:
            print(f"User ID '{user_id_str}' (originally {user_id}) not found in user encoder. Skipping model-based recommendations.")
            
        # --- Bổ sung sản phẩm phổ biến nếu không đủ số lượng gợi ý ---
        final_recommendations = model_recommendations.copy()
        
        if len(final_recommendations) < MINIMUM_RECOMMENDATION_COUNT:
            print(f"Not enough model recommendations ({len(final_recommendations)}), adding popular fallbacks")
            
            # Lọc các sản phẩm phổ biến, chỉ lấy những sản phẩm chưa có trong danh sách gợi ý
            remaining_popular = [pid for pid in popular_products if pid not in final_recommendations]
            
            # Số lượng sản phẩm cần thêm vào
            products_to_add = min(MINIMUM_RECOMMENDATION_COUNT - len(final_recommendations), len(remaining_popular))
            
            # Thêm sản phẩm phổ biến vào danh sách gợi ý
            final_recommendations.extend(remaining_popular[:products_to_add])
            
            print(f"Added {products_to_add} popular items as fallback, final count: {len(final_recommendations)}")
        
        return final_recommendations

    except ValueError as e:
        print(f"ValueError occurred for user_id {user_id}. Potentially invalid user ID or issue during transform/inverse_transform.")
        print(f"Error details: {e}")
        # Trả về danh sách sản phẩm phổ biến nếu có lỗi
        print(f"Falling back to top popular products")
        return popular_products[:MINIMUM_RECOMMENDATION_COUNT]
    except IndexError:
         # Xảy ra nếu user_idx nằm ngoài phạm vi của interaction_matrix (ít khả năng nếu encoder đúng)
         print(f"Internal Error: User index out of bounds for interaction matrix.")
         print(f"Falling back to top popular products")
         return popular_products[:MINIMUM_RECOMMENDATION_COUNT]
    except Exception as e:
        # Bắt các lỗi khác không mong muốn
        print(f"An unexpected error occurred for user {user_id}: {e}")
        print(f"Falling back to top popular products")
        return popular_products[:MINIMUM_RECOMMENDATION_COUNT]

# --- Health check endpoint ---
@app.get("/health", status_code=200, summary="Health Check")
async def health_check():
    """
    Simple health check endpoint to verify the service is running.
    This is used by Kubernetes liveness probe.
    """
    # Check if the model and other required artifacts are loaded
    if not all(key in app_state for key in ["model", "user_encoder", "product_encoder", "interaction_matrix"]):
        raise HTTPException(status_code=503, detail="Service not fully initialized: Required artifacts not loaded.")
    
    return {"status": "ok", "service": SERVICE_NAME, "timestamp": time.time()}

# --- Chạy server (khi chạy trực tiếp file này, chỉ dùng để debug) ---
if __name__ == "__main__":
    import uvicorn
    # Chạy từ thư mục gốc của service (recommendation-service)
    # uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
    print("To run the server, execute:")
    print("cd services/recommendation-service")
    print("uvicorn app.main:app --reload --host 0.0.0.0 --port 8090") 