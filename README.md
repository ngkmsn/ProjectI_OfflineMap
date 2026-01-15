# Ứng dụng Tìm đường Hà Nội (Java + OSM)

Một ứng dụng Java nhẹ sử dụng dữ liệu OpenStreetMap (OSM) để:

- **Tìm đường (Routing)**: Tìm đường đi ngắn nhất giữa hai điểm.
- **TSP (Bài toán người bán hàng)**: Tìm lộ trình tối ưu đi qua nhiều điểm (dùng vét cạn cho số lượng điểm nhỏ).
- **Bản đồ tương tác**: Giao diện web sử dụng thư viện Leaflet.

## Yêu cầu hệ thống

- **Java 17** hoặc cao hơn.
- **Maven 3.6** hoặc cao hơn.
- Kết nối Internet (để tải thư viện và hiển thị bản đồ).

## Cài đặt & Dữ liệu

1.  **Chuẩn bị thư mục dữ liệu**:
    Ứng dụng sẽ tìm dữ liệu OSM trong thư mục `data` tại thư mục gốc của dự án.

    ```bash
    mkdir -p data
    ```

2.  **Tải dữ liệu bản đồ**:
    Tải file OSM PBF cho khu vực bạn cần (ví dụ: Hà Nội, Việt Nam).
    - Nguồn tải: [Geofabrik - Vietnam](https://download.geofabrik.de/asia/vietnam.html)
    - Đổi tên file thành `hanoi.osm.pbf` và đặt vào thư mục `data`:
      `data/hanoi.osm.pbf`

    *Lưu ý: Trong lần chạy đầu tiên, ứng dụng sẽ xử lý file PBF và tạo cache đồ thị trong `data/graph-cache`. Quá trình này có thể mất vài phút.*

## Cách chạy ứng dụng

Ứng dụng có thể chạy trên bất kì nền tảng nào (macOS, Linux, Windows) đã cài Java và Maven.

### Cách 1: Sử dụng Script (Linux/macOS)

```bash
chmod +x run.sh
./run.sh
```

### Cách 2: Build & Chạy thủ công

1.  **Build dự án**:
    ```bash
    mvn clean package
    ```

2.  **Chạy ứng dụng**:
    ```bash
    java -jar target/hanoi-map-routing-1.0.0-SNAPSHOT-jar-with-dependencies.jar
    ```

## Sử dụng

Khi server đã khởi động, mở trình duyệt web và truy cập:

**http://localhost:4567**

### Tính năng
- **Chế độ 2 điểm**: Chọn 2 điểm trên bản đồ để xem đường đi ngắn nhất (màu Xanh).
- **Chế độ TSP**: Chọn nhiều điểm (tối đa 10) để xem lộ trình tối ưu đi qua tất cả các điểm (màu Đỏ).
- **Xóa điểm**: Xóa tất cả các điểm đánh dấu và đường đi hiện tại.

## API Endpoints

- `GET /api/health`: Kiểm tra trạng thái server.
- `GET /api/route`: Tính toán đường đi giữa 2 điểm.
  - Tham số: `fromLat`, `fromLon`, `toLat`, `toLon`
- `POST /api/tsp`: Giải bài toán TSP cho danh sách các điểm.
  - Body: Mảng JSON chứa các đối tượng `{ "lat": ..., "lon": ... }`.

## Công nghệ sử dụng

- **Backend**: Java 17, Spark Java (Web Framework).
- **Routing Engine**: Tự cài đặt thuật toán A* (SimpleRoutingEngine) & Tích hợp GraphHopper.
- **Frontend**: HTML5, Leaflet.js, OpenStreetMap tiles.
