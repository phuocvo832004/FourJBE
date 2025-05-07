from kafka import KafkaConsumer
import json
import os
from dotenv import load_dotenv
import logging
from datetime import datetime, timezone
import uuid
from azure.storage.blob import BlobServiceClient

load_dotenv()

# Cấu hình logging cơ bản
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')
logger = logging.getLogger(__name__)

KAFKA_BOOTSTRAP_SERVERS = os.getenv('KAFKA_BOOTSTRAP_SERVERS', 'kafka:9092')
ORDER_CREATED_TOPIC = os.getenv('ORDER_CREATED_TOPIC', 'order_created_topic')

# Cấu hình Azure Blob Storage (Thêm mới)
AZURE_CONNECTION_STRING = os.getenv('AZURE_CONNECTION_STRING')
AZURE_BLOB_CONTAINER_NAME = os.getenv('AZURE_CONTAINER', 'recommendation-data')
NEW_INTERACTIONS_PATH = os.getenv('NEW_DATA_PATH', 'processed-interactions/new/')

def start_kafka_consumer() -> None:
    try:
        consumer = KafkaConsumer(
            ORDER_CREATED_TOPIC,
            bootstrap_servers=KAFKA_BOOTSTRAP_SERVERS,
            auto_offset_reset='earliest',  
            enable_auto_commit=True,
            group_id='recommendation-service-group', 
            value_deserializer=lambda m: json.loads(m.decode('utf-8'))
        )
    except Exception as e:
        logger.error(f"Không thể khởi tạo Kafka consumer: {e}")
        return

    logger.info(f"Bắt đầu Kafka consumer cho topic: {ORDER_CREATED_TOPIC} trên servers: {KAFKA_BOOTSTRAP_SERVERS}")

    for message in consumer:
        try:
            logger.info(f"Nhận được message: {message.value}")
            order_data = message.value
            user_id = order_data.get('userId')
            items = order_data.get('items')

            if user_id and items:
                process_order_for_recommendations(user_id, items)
            else:
                logger.warning(f"Message không hợp lệ hoặc thiếu thông tin: {message.value}")
        except json.JSONDecodeError as e:
            logger.error(f"Lỗi giải mã JSON từ message: {message.value}. Lỗi: {e}")
        except Exception as e:
            logger.error(f"Lỗi không xác định khi xử lý message: {message.value}. Lỗi: {e}")

def process_order_for_recommendations(user_id: str, items: list) -> None:
    logger.info(f"Bắt đầu xử lý đơn hàng cho user {user_id} với các sản phẩm: {items}")

    if not items:
        logger.warning(f"Không có sản phẩm nào trong đơn hàng của user {user_id} để xử lý.")
        return

    if not AZURE_CONNECTION_STRING:
        logger.error("AZURE_CONNECTION_STRING không được cấu hình. Không thể lưu tương tác.")
        # Tuỳ chọn: Có thể raise lỗi hoặc xử lý khác nếu không muốn bỏ qua việc lưu trữ
        return

    interactions_to_save = []
    for item_data in items:
        product_id = None
        quantity = 1  # Mặc định quantity là 1

        if isinstance(item_data, dict):
            product_id = item_data.get('productId')
            quantity = item_data.get('quantity', 1)
        else:  # Giả sử item_data là một product_id đơn giản
            product_id = item_data
        
        if product_id is None:
            logger.warning(f"Bỏ qua item do thiếu product_id: {item_data} cho user {user_id}")
            continue

        try:
            # Đảm bảo quantity là số nguyên
            current_quantity = int(quantity)
        except (ValueError, TypeError):
            logger.warning(f"Quantity không hợp lệ ('{quantity}') cho product_id {product_id}, user {user_id}. Sử dụng mặc định là 1.")
            current_quantity = 1
            
        interactions_to_save.append({
            'user_id': str(user_id),
            'product_id': str(product_id),
            'quantity': current_quantity,
            'timestamp': datetime.now(timezone.utc).isoformat()
        })

    if not interactions_to_save:
        logger.info(f"Không có tương tác hợp lệ nào được tạo cho user {user_id} từ đơn hàng: {items}")
        return

    try:
        blob_service_client = BlobServiceClient.from_connection_string(AZURE_CONNECTION_STRING)
        # Đảm bảo NEW_INTERACTIONS_PATH kết thúc bằng dấu /
        base_path = NEW_INTERACTIONS_PATH.rstrip('/') + '/'
        blob_name = f"{base_path}{uuid.uuid4()}.json"
        
        blob_client = blob_service_client.get_blob_client(container=AZURE_BLOB_CONTAINER_NAME, blob=blob_name)
        
        # Chuyển đổi danh sách tương tác thành chuỗi JSON
        interactions_json = json.dumps(interactions_to_save, indent=2)
        
        blob_client.upload_blob(interactions_json, overwrite=True)
        logger.info(f"Đã lưu {len(interactions_to_save)} tương tác cho user {user_id} vào Blob Storage: {AZURE_BLOB_CONTAINER_NAME}/{blob_name}")

    except Exception as e:
        logger.error(f"Lỗi khi lưu tương tác vào Azure Blob Storage cho user {user_id}: {e}")
        # Xem xét việc retry hoặc đưa message vào dead-letter-queue ở đây


    logger.info(f"Hoàn tất xử lý đơn hàng (lưu tương tác) cho user {user_id}.")


if __name__ == '__main__':
    logger.info("Đang khởi tạo Kafka consumer...")
    start_kafka_consumer()

