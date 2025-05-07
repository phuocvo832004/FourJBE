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
import consul
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

# --- Cấu hình Consul ---
CONSUL_HOST = os.getenv("CONSUL_HOST", "consul")
CONSUL_PORT = int(os.getenv("CONSUL_PORT", "8500"))
SERVICE_NAME = "recommendation-service"
SERVICE_ID = f"{SERVICE_NAME}-{socket.gethostname()}"
SERVICE_PORT = int(os.getenv("SERVICE_PORT", "8090"))
SERVICE_CHECK_INTERVAL = "10s"
SERVICE_CHECK_TIMEOUT = "5s"
SERVICE_CHECK_DEREGISTER_AFTER = "30s"

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

# --- Hàm đăng ký dịch vụ với Consul ---
def register_with_consul():
    c = consul.Consul(host=CONSUL_HOST, port=CONSUL_PORT)
    
    # Đăng ký service với Consul
    service_definition = {
        "name": SERVICE_NAME,
        "service_id": SERVICE_ID,
        "address": socket.gethostname(),  # Sử dụng tên hostname của container
        "port": SERVICE_PORT,
        "tags": ["api", "recommendations", "python", "fastapi"],
        "check": {
            "http": f"http://{socket.gethostname()}:{SERVICE_PORT}/health",
            "interval": SERVICE_CHECK_INTERVAL,
            "timeout": SERVICE_CHECK_TIMEOUT,
            "deregister_critical_service_after": SERVICE_CHECK_DEREGISTER_AFTER
        }
    }
    
    try:
        c.agent.service.register(**service_definition)
        print(f"Registered service with Consul: {SERVICE_ID}")
        return True
    except Exception as e:
        print(f"Failed to register with Consul: {e}")
        return False

# --- Hàm hủy đăng ký dịch vụ khỏi Consul ---
def deregister_from_consul():
    try:
        c = consul.Consul(host=CONSUL_HOST, port=CONSUL_PORT)
        c.agent.service.deregister(SERVICE_ID)
        print(f"Deregistered service from Consul: {SERVICE_ID}")
        return True
    except Exception as e:
        print(f"Failed to deregister from Consul: {e}")
        return False

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
    Tải model mới nhất từ Cosmos DB và các artifacts liên quan từ Azure Blob Storage.
    """
    print(f"Loading artifacts: Model from Cosmos DB ({COSMOS_DATABASE_NAME}/{COSMOS_CONTAINER_NAME}), others from Blob ({AZURE_CONTAINER})")

    # Kiểm tra cấu hình
    if not AZURE_CONNECTION_STRING or not COSMOS_ENDPOINT or not COSMOS_KEY:
        print("Error: Azure Storage or Cosmos DB connection details missing.")
        return False

    temp_dir = tempfile.mkdtemp()
    print(f"Created temporary directory for downloads: {temp_dir}")
    model = None
    user_encoder_blob_name = None
    product_encoder_blob_name = None
    interaction_matrix_blob_name = None

    try:
        # 1. Kết nối Cosmos DB và tải model mới nhất
        print("Attempting to connect to Cosmos DB...")
        cosmos_client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
        
        print(f"Connecting to database: {COSMOS_DATABASE_NAME}")
        try:
            database = cosmos_client.get_database_client(COSMOS_DATABASE_NAME)
            print(f"Successfully connected to database: {COSMOS_DATABASE_NAME}")
        except Exception as db_err:
            print(f"Error connecting to database {COSMOS_DATABASE_NAME}: {db_err}")
            return False
        
        print(f"Connecting to container: {COSMOS_CONTAINER_NAME}")
        try:
            container = database.get_container_client(COSMOS_CONTAINER_NAME)
            print(f"Successfully connected to container: {COSMOS_CONTAINER_NAME}")
        except Exception as container_err:
            print(f"Error connecting to container {COSMOS_CONTAINER_NAME}: {container_err}")
            return False

        query = "SELECT TOP 1 * FROM c WHERE c.documentType = 'modelDescriptor' ORDER BY c.timestamp DESC"
        print(f"Executing query to find model descriptor: {query}")
        try:
            items = list(container.query_items(query=query, enable_cross_partition_query=True))
            print(f"Query executed successfully. Got {len(items)} results for model descriptor.")
        except Exception as query_err:
            print(f"Error executing query: {query_err}")
            return False

        if items:
            model_descriptor_doc = items[0] # This document describes the model and associated artifacts
            print(f"Found latest model descriptor in Cosmos DB: ID={model_descriptor_doc.get('id')}, Timestamp={model_descriptor_doc.get('timestamp')}")
            
            # Determine the partition key for the model chunks.
            # Default to "alsmodel" which is PARTITION_KEY_VALUE from app/uploadmodelcosmos.py
            # Ideally, model_descriptor_doc might specify this if multiple model versions use different chunk partition keys.
            model_chunk_partition_key = model_descriptor_doc.get('modelChunkPartitionKey', "alsmodel")
            print(f"Attempting to load model chunks using partition key: '{model_chunk_partition_key}'")

            # Query all chunks for this model, ordered by chunk_index
            chunk_query = "SELECT * FROM c WHERE c.partition_key = @pk ORDER BY c.chunk_index ASC"
            chunk_query_params = [dict(name="@pk", value=model_chunk_partition_key)]
            
            try:
                model_chunk_docs = list(container.query_items(
                    query=chunk_query,
                    parameters=chunk_query_params,
                    # enable_cross_partition_query can be False as we are targeting a specific partition_key
                    enable_cross_partition_query=True
                ))
            except Exception as chunk_query_err:
                print(f"Error querying model chunks for partition key '{model_chunk_partition_key}': {chunk_query_err}")
                return False

            if not model_chunk_docs:
                print(f"Error: No model chunks found for partition key '{model_chunk_partition_key}'.")
                print("Ensure model chunks were uploaded with this partition_key and have 'chunk_index' and 'file_chunk' fields.")
                return False

            print(f"Retrieved {len(model_chunk_docs)} model chunks for partition key '{model_chunk_partition_key}'.")

            # Optional: Verify completeness if total_chunks is available
            if model_chunk_docs and 'total_chunks' in model_chunk_docs[0]:
                expected_total_chunks = model_chunk_docs[0]['total_chunks']
                if len(model_chunk_docs) != expected_total_chunks:
                    print(f"Warning: Expected {expected_total_chunks} chunks based on 'total_chunks' field in the first chunk, but retrieved {len(model_chunk_docs)} chunks. The model might be incomplete.")
                    # Depending on requirements, this could be a fatal error (return False)
                else:
                    print(f"Successfully retrieved all {expected_total_chunks} expected model chunks.")
            else:
                print("Warning: 'total_chunks' field not found in the first model chunk. Cannot verify chunk completeness against an expected count.")

            # Assemble the model from chunks
            try:
                # Sort again just in case the DB didn't strictly honor ORDER BY (highly unlikely but safe)
                # model_chunk_docs.sort(key=lambda doc: doc['chunk_index']) 
                base64_encoded_model_str = "".join([chunk_doc['file_chunk'] for chunk_doc in model_chunk_docs])
            except KeyError as ke:
                print(f"Error assembling chunks: 'file_chunk' or 'chunk_index' missing in some chunk documents. {ke}")
                return False
            
            if not base64_encoded_model_str:
                print("Error: Assembled base64 model string is empty. Chunks might be empty or missing 'file_chunk'.")
                return False

            print(f"Successfully assembled base64 encoded model string from {len(model_chunk_docs)} chunks (total length: {len(base64_encoded_model_str)}).")

            # Decode Base64 and deserialize model
            try:
                model_bytes = base64.b64decode(base64_encoded_model_str)
                print(f"Base64 decode successful, got {len(model_bytes)} bytes for the model.")
                model = pickle.loads(model_bytes)
                print("Model deserialized successfully from assembled chunks.")
            except Exception as decode_err:
                print(f"Failed to decode/deserialize assembled model data: {decode_err}")
                model = None # Ensure model is None if deserialization fails

            if model:
                print(f"Model loaded successfully: {type(model)}")
                app_state["model"] = model
                # Get artifact blob names from the model_descriptor_doc
                user_encoder_blob_name = model_descriptor_doc.get('userEncoderBlob', AZURE_USER_ENCODER_BLOB_NAME)
                product_encoder_blob_name = model_descriptor_doc.get('productEncoderBlob', AZURE_PRODUCT_ENCODER_BLOB_NAME)
                interaction_matrix_blob_name = model_descriptor_doc.get('interactionMatrixBlob', AZURE_INTERACTION_MATRIX_BLOB_NAME)
                print(f"Artifact blob names from model descriptor:")
                print(f"- User encoder: {user_encoder_blob_name}")
                print(f"- Product encoder: {product_encoder_blob_name}")
                print(f"- Interaction matrix: {interaction_matrix_blob_name}")
            else:
                print("Error: Failed to obtain a valid model object after attempting to load from chunks.")
                return False
        else:
            print("Error: No model descriptor document found in Cosmos DB (via TOP 1 query).")
            print("Ensure a descriptor document is present, ordered by 'timestamp', and that it can point to the model chunks if necessary.")
            return False

        # 2. Kết nối Blob Storage và tải các artifacts khác dựa trên tên từ Cosmos DB
        print(f"Connecting to Azure Blob Storage: container={AZURE_CONTAINER}")
        try:
            blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
            blob_container_client = blob_service_client.get_container_client(AZURE_CONTAINER)
            print("Successfully connected to Blob Storage")
        except Exception as blob_err:
            print(f"Error connecting to Blob Storage: {blob_err}")
            return False

        artifacts_loaded = True

        # Tải User Encoder
        if user_encoder_blob_name:
            user_encoder_path = Path(temp_dir) / "user_encoder.pkl"
            print(f"Downloading user encoder from blob: {user_encoder_blob_name}")
            try:
                user_encoder_blob = blob_container_client.get_blob_client(user_encoder_blob_name)
                if user_encoder_blob.exists():
                    print(f"User encoder blob exists")
                    if download_blob_to_file(user_encoder_blob, user_encoder_path):
                        print(f"Downloaded user encoder to: {user_encoder_path}")
                        with open(user_encoder_path, 'rb') as f:
                            app_state["user_encoder"] = pickle.load(f)
                        print(f"User encoder loaded successfully with {len(app_state['user_encoder'].classes_)} classes")
                    else:
                        print(f"Failed to download user encoder to file")
                        artifacts_loaded = False
                else:
                    print(f"User encoder blob does not exist at: {user_encoder_blob_name}")
                    artifacts_loaded = False
            except Exception as ue_err:
                print(f"Error accessing user encoder blob: {ue_err}")
                artifacts_loaded = False
        else:
             print("Error: User encoder blob name not found in model descriptor.")
             artifacts_loaded = False


        # Tải Product Encoder
        if product_encoder_blob_name:
            product_encoder_path = Path(temp_dir) / "product_encoder.pkl"
            print(f"Downloading product encoder from blob: {product_encoder_blob_name}")
            try:
                product_encoder_blob = blob_container_client.get_blob_client(product_encoder_blob_name)
                if product_encoder_blob.exists():
                    print(f"Product encoder blob exists")
                    if download_blob_to_file(product_encoder_blob, product_encoder_path):
                        print(f"Downloaded product encoder to: {product_encoder_path}")
                        with open(product_encoder_path, 'rb') as f:
                            app_state["product_encoder"] = pickle.load(f)
                        print(f"Product encoder loaded successfully with {len(app_state['product_encoder'].classes_)} classes")
                    else:
                        print(f"Failed to download product encoder to file")
                        artifacts_loaded = False
                else:
                    print(f"Product encoder blob does not exist at: {product_encoder_blob_name}")
                    artifacts_loaded = False
            except Exception as pe_err:
                print(f"Error accessing product encoder blob: {pe_err}")
                artifacts_loaded = False
        else:
            print("Error: Product encoder blob name not found in model descriptor.")
            artifacts_loaded = False


        # Tải Interaction Matrix
        if interaction_matrix_blob_name:
            interaction_matrix_path = Path(temp_dir) / "interaction_matrix.npz"
            print(f"Downloading interaction matrix from blob: {interaction_matrix_blob_name}")
            try:
                interaction_matrix_blob = blob_container_client.get_blob_client(interaction_matrix_blob_name)
                if interaction_matrix_blob.exists():
                    print(f"Interaction matrix blob exists")
                    if download_blob_to_file(interaction_matrix_blob, interaction_matrix_path):
                        print(f"Downloaded interaction matrix to: {interaction_matrix_path}")
                        app_state["interaction_matrix"] = load_npz(interaction_matrix_path)
                        print(f"Interaction matrix loaded successfully with shape: {app_state['interaction_matrix'].shape}")
                    else:
                        print(f"Failed to download interaction matrix to file")
                        artifacts_loaded = False
                else:
                    print(f"Interaction matrix blob does not exist at: {interaction_matrix_blob_name}")
                    artifacts_loaded = False
            except Exception as im_err:
                print(f"Error accessing interaction matrix blob: {im_err}")
                artifacts_loaded = False
        else:
            print("Error: Interaction matrix blob name not found in model descriptor.")
            artifacts_loaded = False


        # Kiểm tra tính nhất quán sau khi tải
        if artifacts_loaded:
            user_encoder = app_state.get("user_encoder")
            product_encoder = app_state.get("product_encoder")
            interaction_matrix = app_state.get("interaction_matrix")
            if user_encoder and product_encoder and interaction_matrix is not None:
                 num_products_encoder = len(product_encoder.classes_)
                 num_users_matrix, num_products_matrix = interaction_matrix.shape
                 print(f"Loaded artifact dimensions: Encoder Products={num_products_encoder}, Matrix Shape=({num_users_matrix}x{num_products_matrix})")
                 if num_products_encoder != num_products_matrix:
                      print(f"CRITICAL WARNING: Mismatch in product counts! Encoder knows {num_products_encoder}, Matrix has {num_products_matrix} columns.")
                 else:
                      print("Product count consistency check passed.")
                 
                 # Tính toán các sản phẩm phổ biến để dùng làm fallback
                 app_state["popular_products"] = compute_popular_products(interaction_matrix, product_encoder)
            else:
                 print("Warning: Some artifacts were not loaded correctly for consistency check.")
                 artifacts_loaded = False


        return artifacts_loaded

    except Exception as e:
        print(f"Unexpected error loading artifacts from Azure: {str(e)}")
        import traceback
        traceback.print_exc()
        return False
    finally:
        # Xóa thư mục tạm
        import shutil
        shutil.rmtree(temp_dir, ignore_errors=True)
        print(f"Cleaned up temporary directory: {temp_dir}")

# --- Sự kiện Startup: Load model và các artifacts, đăng ký với Consul ---
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

    # Đăng ký service với Consul (ngay cả khi không load được model)
    max_attempts = 5
    attempt = 0
    registered = False

    while attempt < max_attempts and not registered:
        attempt += 1
        print(f"Attempt {attempt}/{max_attempts} to register with Consul")
        registered = register_with_consul()
        if not registered and attempt < max_attempts:
            sleep_time = 5 * attempt  # Backoff strategy
            print(f"Sleeping for {sleep_time}s before retry")
            time.sleep(sleep_time)

    if not registered:
        print("WARNING: Failed to register with Consul after multiple attempts")

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

# --- Sự kiện Shutdown: Hủy đăng ký khỏi Consul ---
@app.on_event("shutdown")
async def shutdown_events():
    print("Service shutting down, deregistering from Consul")
    deregister_from_consul()

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

    try:
        model_recommendations = []
        
        # Kiểm tra xem user_id có trong encoder không  
        if user_id in user_encoder.classes_:
            # 1. Chuyển user_id thành user_idx
            print(f"Looking up user_id: {user_id}")
            user_idx = user_encoder.transform([user_id])[0]
            print(f"Found user_idx: {user_idx}") # Thêm log user_idx

            # 2. Lấy vector tương tác của user từ sparse matrix
            # Đảm bảo interaction_matrix là CSR để truy cập hàng hiệu quả
            if not isinstance(interaction_matrix, csr_matrix):
                 interaction_matrix = interaction_matrix.tocsr() # Chuyển đổi nếu cần
            user_items_sparse = interaction_matrix[user_idx]
            print(f"User items vector shape: {user_items_sparse.shape}, Non-zero elements: {user_items_sparse.nnz}") # Thêm log vector

            # 3. Gọi hàm recommend
            print("Calling model.recommend...")
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
            print(f"User ID {user_id} not found in user encoder. Skipping model-based recommendations.")
            
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

# --- Endpoint kiểm tra sức khỏe (tùy chọn) ---
@app.get("/health", status_code=200, summary="Health Check")
async def health_check():
    """Basic health check endpoint."""
    return {"status": "ok"}

# --- Chạy server (khi chạy trực tiếp file này, chỉ dùng để debug) ---
if __name__ == "__main__":
    import uvicorn
    # Chạy từ thư mục gốc của service (recommendation-service)
    # uvicorn app.main:app --reload --host 0.0.0.0 --port 8001
    print("To run the server, execute:")
    print("cd services/recommendation-service")
    print("uvicorn app.main:app --reload --host 0.0.0.0 --port 8090") # Chọn port khác 8080 nếu cần