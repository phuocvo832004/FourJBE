# %%
import pandas as pd
import numpy as np
from scipy.sparse import csr_matrix


# %%
# Đọc dữ liệu đơn hàng
orders = pd.read_csv('orders.xls')

# Đọc chi tiết sản phẩm đã mua trong đơn hàng
order_products_prior = pd.read_csv('order_products__prior.xls')
order_products_train = pd.read_csv('order_products__train.xls')

# Đọc sản phẩm
products = pd.read_csv('products.xls')


# %%
# Chỉ lấy đơn hàng "prior"
orders_prior = orders[orders['eval_set'] == 'prior']

# Join đơn hàng với sản phẩm đã mua
prior_merged = pd.merge(order_products_prior, orders_prior, on='order_id')

# Chỉ lấy các cột cần thiết
user_product = prior_merged[['user_id', 'product_id']]

user_product.head()


# %%
# Đếm số lần user mua sản phẩm
user_product_counts = user_product.groupby(['user_id', 'product_id']).size().reset_index(name='times_purchased')

user_product_counts.head()


# %%
# Encode user_id và product_id thành chỉ số (index bắt đầu từ 0)
from sklearn.preprocessing import LabelEncoder

user_encoder = LabelEncoder()
product_encoder = LabelEncoder()

user_product_counts['user_idx'] = user_encoder.fit_transform(user_product_counts['user_id'])
user_product_counts['product_idx'] = product_encoder.fit_transform(user_product_counts['product_id'])

# Tạo sparse matrix
interaction_matrix = csr_matrix((
    user_product_counts['times_purchased'],
    (user_product_counts['user_idx'], user_product_counts['product_idx'])
))

interaction_matrix


# %%
print('Number of users:', interaction_matrix.shape[0])
print('Number of products:', interaction_matrix.shape[1])


# %%
import implicit

# Dùng GPU nếu có (cuda), còn không thì dùng CPU
model = implicit.als.AlternatingLeastSquares(
    factors=50,          # số lượng latent factors (embedding size)
    regularization=0.01, # hệ số regularization để tránh overfitting
    iterations=20,       # số vòng lặp
    random_state=42
)

# implicit yêu cầu chuyển data thành item-user matrix (transpose)
item_user_matrix = interaction_matrix.T

# Fit model
model.fit(item_user_matrix)


# %%
target_user_id = 1
user_idx = user_encoder.transform([target_user_id])[0]

# Lấy sparse vector user
user_items_single = item_user_matrix.T[user_idx]

# Recommend
recommended = model.recommend(
    userid=0,
    user_items=user_items_single,
    N=10,
    filter_already_liked_items=True
)




# %%
# Lấy item_idx và ép kiểu int
recommended_product_idxs = [int(item[0]) for item in recommended]

# Decode product_idx → product_id
recommended_product_ids = product_encoder.inverse_transform(recommended_product_idxs)

# Lấy tên sản phẩm
recommended_products = products[products['product_id'].isin(recommended_product_ids)]

print(recommended_products[['product_id', 'product_name']])

# %%
import pickle

# Lưu model
with open('alsmodel.pkl', 'wb') as f:
    pickle.dump(model, f)


# %%
# Tải lại model
with open('alsmodel.pkl', 'rb') as f:
    loaded_model = pickle.load(f)

# Kiểm tra model đã tải lại có thể dự đoán
recommended = loaded_model.recommend(
    userid=0,
    user_items=user_items_single,
    N=10,
    filter_already_liked_items=True
)

# In ra các sản phẩm được đề xuất
recommended_product_idxs = [int(item[0]) for item in recommended]
recommended_product_ids = product_encoder.inverse_transform(recommended_product_idxs)
recommended_products = products[products['product_id'].isin(recommended_product_ids)]

print(recommended_products[['product_id', 'product_name']])


# %%
import pickle
from scipy.sparse import save_npz

# --- Thêm đoạn code này ---
# Lưu user encoder
with open('user_encoder.pkl', 'wb') as f:
    pickle.dump(user_encoder, f)

# Lưu product encoder
with open('product_encoder.pkl', 'wb') as f:
    pickle.dump(product_encoder, f)

# Lưu user-item interaction matrix (cần cho model.recommend)
# Lưu ý: Đây là ma trận user-item gốc, không phải ma trận đã transpose (item-user) dùng để fit
save_npz('interaction_matrix.npz', interaction_matrix)
# --- Kết thúc đoạn code thêm ---



# %%
import pandas as pd
import random

# Đọc file gốc
df = pd.read_csv("products.csv")

# Giả sử bạn đã có mapping giữa category_id gốc với category_id mới trong Neon
category_mapping = {
    1: 1,    # frozen
    13: 2,   # other
    7: 3,    # bakery
    19: 4    # produce
}

# Thêm cột category_id mới dựa trên mapping
df["category_id"] = df["category_id"].map(category_mapping)

# Đảm bảo loại bỏ các bản ghi có category_id không hợp lệ
df = df.dropna(subset=["category_id"])

# Thêm cột 'active'
df["active"] = True

# Tạo cột 'price' với giá trị random từ 10.00 đến 1000.00
df["price"] = [random.uniform(10.00, 1000.00) for _ in range(len(df))]

# Kiểm tra nếu cột 'stock_quantity' không tồn tại, ta sẽ tạo ra một cột với số lượng mặc định
if 'stock_quantity' not in df.columns:
    df["stock_quantity"] = 100  # Gán số lượng mặc định nếu không có cột 'stock_quantity'

# Đổi tên cột product_name → name, giữ lại các cột cần thiết
df_clean = df.rename(columns={"product_name": "name"})[["name", "category_id", "active", "price", "stock_quantity"]]

# Xuất ra file CSV mới
df_clean.to_csv("products_clean.csv", index=False)

print("✅ Đã lưu file products_clean.csv")
df_clean


# %%
import csv

input_file = 'products_clean.csv'
output_file = 'products_clean_fixed.csv'

with open(input_file, newline='', encoding='utf-8') as infile, \
     open(output_file, 'w', newline='', encoding='utf-8') as outfile:
    
    reader = csv.DictReader(infile)
    fieldnames = reader.fieldnames
    writer = csv.DictWriter(outfile, fieldnames=fieldnames)
    
    writer.writeheader()
    for row in reader:
        row['category_id'] = str(int(float(row['category_id'])))  # Convert float -> int -> str
        row['active'] = row['active'].lower()  # Ensure 'true'/'false' in lowercase
        writer.writerow(row)

print("✅ Đã xử lý xong file:", output_file)



