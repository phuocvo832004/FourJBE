import base64
import math
import os
from azure.cosmos import CosmosClient, PartitionKey

PARTITION_KEY_VALUE = "alsmodel"
COSMOS_ENDPOINT = os.getenv('COSMOS_ENDPOINT', "")
COSMOS_KEY = os.getenv('COSMOS_KEY')
COSMOS_DATABASE_NAME = os.getenv('COSMOS_DATABASE_NAME', "")
COSMOS_CONTAINER_NAME = os.getenv('COSMOS_CONTAINER_NAME', "")

# === BƯỚC 1: ĐỌC FILE .PKL VÀ MÃ HÓA BASE64 ===
with open('./artifacts/alsmodel.pkl', 'rb') as file:
    file_data = file.read()
    base64_encoded = base64.b64encode(file_data).decode('utf-8')

# === BƯỚC 2: CHIA NHỎ DỮ LIỆU BASE64 ===
chunk_size = 50000  # số ký tự base64 mỗi phần (có thể điều chỉnh nhỏ hơn nếu vẫn lỗi)
chunks = [base64_encoded[i:i + chunk_size] for i in range(0, len(base64_encoded), chunk_size)]

# === BƯỚC 3: KẾT NỐI TỚI COSMOS DB ===
if not COSMOS_KEY:
    raise ValueError("COSMOS_KEY không được cấu hình trong biến môi trường")

client = CosmosClient(COSMOS_ENDPOINT, credential=COSMOS_KEY)
database = client.get_database_client(COSMOS_DATABASE_NAME)
container = database.get_container_client(COSMOS_CONTAINER_NAME)

# === BƯỚC 4: GỬI TỪNG PHẦN LÊN COSMOS DB ===
for index, chunk in enumerate(chunks):
    document = {
        "id": f"{PARTITION_KEY_VALUE}_part_{index}", 
        "partition_key": PARTITION_KEY_VALUE,
        "chunk_index": index,
        "total_chunks": len(chunks),
        "file_chunk": chunk
    }
    container.upsert_item(document)

print(f"Upload thành công {len(chunks)} phần của alsmodel.pkl lên Azure Cosmos DB.")

