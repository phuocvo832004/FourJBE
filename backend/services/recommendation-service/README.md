# Recommendation Service

Dịch vụ gợi ý sản phẩm sử dụng mô hình ALS (Alternating Least Squares) được tích hợp với Azure Blob Storage để xử lý dữ liệu giao dịch.

## Tính năng

- **Gợi ý sản phẩm**: Cung cấp API để lấy gợi ý sản phẩm cho người dùng dựa trên lịch sử mua hàng
- **ETL**: Dữ liệu đơn hàng được đưa vào Azure Blob Storage.
- **Cập nhật mô hình**: Tự động cập nhật mô hình theo lịch trình, sử dụng dữ liệu mới từ Azure Blob Storage.
- **Tích hợp Consul**: Hỗ trợ đăng ký dịch vụ để phát hiện dịch vụ

## Cấu trúc dự án

```
recommendation-service/
├── app/
│   ├── __init__.py
│   ├── main.py                # API chính (FastAPI)
│   ├── batch_update.py        # Job cập nhật mô hình theo lịch
│   ├── setup_storage.py       # Script thiết lập Azure Blob Storage
│   └── entrypoint.sh          # Script tiện ích (có thể dùng cho khởi động hoặc tác vụ khác)
├── artifacts/                  # Thư mục chứa mô hình và các artifacts cục bộ
├── Dockerfile                  # Định nghĩa build Docker image
├── requirements.txt            # Danh sách thư viện Python
├── .env.example              # Mẫu biến môi trường
├── README.md                   # Tài liệu hướng dẫn này
├── deployment.yaml             # Cấu hình triển khai Kubernetes Deployment
├── service.yaml                # Cấu hình triển khai Kubernetes Service
├── cronjob.yaml                # Cấu hình Kubernetes CronJob cho batch update
└── secrets.yaml                # Mẫu cấu trúc Kubernetes Secret (không chứa giá trị thật)
```

## Luồng dữ liệu

1. **Thu thập dữ liệu**: Dữ liệu đơn hàng (tương tác người dùng-sản phẩm) được chuẩn bị và tải lên Azure Blob Storage.
2. **Xử lý dữ liệu**: Dữ liệu tương tác từ Azure Blob Storage được sử dụng để cập nhật mô hình.
3. **Cập nhật mô hình**: Batch job cập nhật mô hình định kỳ, huấn luyện lại mô hình ALS với dữ liệu mới nhất.
4. **Phục vụ gợi ý**: API gợi ý phục vụ các yêu cầu gợi ý sản phẩm cho người dùng.

## Thiết lập môi trường

1. **Azure Blob Storage**:
   - Tạo một Azure Storage Account
   - Tạo Access Key hoặc Connection String
   - Điền thông tin vào biến môi trường `AZURE_CONNECTION_STRING`
   - Đảm bảo container được chỉ định trong `AZURE_CONTAINER` tồn tại.
   - Dữ liệu tương tác mới cần được đặt trong đường dẫn `NEW_DATA_PATH` (ví dụ: `processed-interactions/new/`) trong container.

## Cài đặt

### Sử dụng Docker

1. Sao chép tệp `env.example` thành `.env` và điều chỉnh các biến môi trường:
   ```bash
   cp env.example .env
   # Chỉnh sửa .env với thông tin của bạn
   ```

2. Xây dựng và chạy Docker container:
   ```bash
   docker build -t recommendation-service .
   # Container chạy uvicorn trực tiếp theo CMD trong Dockerfile
   docker run --env-file .env -p 8090:8090 recommendation-service
   ```

### Triển khai bằng Kubernetes

Các tệp cấu hình Kubernetes được cung cấp:
- `deployment.yaml`: Định nghĩa cách triển khai các pod của ứng dụng API.
- `service.yaml`: Định nghĩa cách expose ứng dụng API ra bên ngoài hoặc trong cluster.
- `cronjob.yaml`: Định nghĩa lịch trình chạy batch job để cập nhật mô hình.
- `secrets.yaml`: Cung cấp một mẫu cấu trúc để tạo Kubernetes Secret chứa các thông tin nhạy cảm.

1. **Tạo Kubernetes Secret:**
   Bạn cần tạo một Secret trong Kubernetes để lưu trữ các biến môi trường nhạy cảm như connection strings, API keys. Dựa theo `secrets.yaml`, bạn có thể tạo secret như sau (thay giá trị `<base64-encoded-...>` bằng giá trị đã mã hóa base64 của bạn):
   ```bash
   # Tạo file my-secrets.yaml dựa trên secrets.yaml với giá trị đã mã hóa
   kubectl apply -f my-secrets.yaml
   ```
   Hoặc tạo trực tiếp bằng `kubectl create secret generic` cho từng giá trị:
   ```bash
   kubectl create secret generic recommendation-secrets \\
     --from-literal=AZURE_CONNECTION_STRING='your_connection_string' \\
     --from-literal=COSMOS_ENDPOINT='your_cosmos_endpoint' \\
     --from-literal=COSMOS_KEY='your_cosmos_key'
   # Thêm các secret khác nếu cần
   ```

2. **Điều chỉnh và áp dụng cấu hình:**
   - Mở các tệp `deployment.yaml`, `service.yaml`, `cronjob.yaml`.
   - **QUAN TRỌNG:** Thay đổi `<your-docker-registry>/recommendation-service:<tag>` trong `deployment.yaml` và `cronjob.yaml` thành đường dẫn Docker image thực tế của bạn.
   - Xem xét và điều chỉnh các cấu hình khác (replicas, resource limits, schedule trong cronjob, service type...) cho phù hợp với môi trường của bạn.
   - Áp dụng các tệp cấu hình:
   ```bash
   kubectl apply -f secrets.yaml # Hoặc my-secrets.yaml nếu bạn tạo file riêng
   kubectl apply -f deployment.yaml
   kubectl apply -f service.yaml
   kubectl apply -f cronjob.yaml
   ```

## API

- **GET /api/v1/recommendations/{user_id}**: Lấy danh sách sản phẩm được gợi ý cho người dùng
- **GET /health**: Endpoint kiểm tra sức khỏe (dùng cho cơ chế khám phá dịch vụ Consul)

## Cấu hình nâng cao

### Cập nhật mô hình
Bạn có thể điều chỉnh lịch trình cập nhật mô hình bằng cách sửa biến môi trường `BATCH_SCHEDULE` theo định dạng crontab. Dữ liệu đầu vào cho quá trình cập nhật được lấy từ `AZURE_CONTAINER` tại đường dẫn `NEW_DATA_PATH`.

### Cấu hình mô hình
Các thông số của mô hình ALS có thể được điều chỉnh thông qua các biến môi trường:
- `ALS_FACTORS`: Số lượng yếu tố ẩn
- `ALS_REGULARIZATION`: Hệ số điều chuẩn
- `ALS_ITERATIONS`: Số lần lặp
- `ALS_ALPHA`: Hệ số tỷ lệ độ tin cậy

## Xử lý sự cố

- **Không thể truy cập Azure Blob Storage**: Xác nhận rằng `AZURE_CONNECTION_STRING` hợp lệ và tài khoản lưu trữ đang hoạt động. Kiểm tra quyền truy cập vào container và các blob cần thiết.
- **Mô hình không cập nhật**: Kiểm tra logs của batch job (`batch_update.py`) và đảm bảo đủ quyền truy cập vào Azure Storage. Đảm bảo có dữ liệu mới trong `NEW_DATA_PATH`. 