package com.fourj.orderservice.service.impl;


import com.fourj.orderservice.dto.*;
import com.fourj.orderservice.exception.*;
import com.fourj.orderservice.messaging.KafkaProducerService;
import com.fourj.orderservice.model.*;
import com.fourj.orderservice.repository.OrderRepository;
import com.fourj.orderservice.service.OrderExportService;
import com.fourj.orderservice.service.OrderService;
import com.fourj.orderservice.service.client.CartClient;
import com.fourj.orderservice.service.client.ProductClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.payos.PayOS;
import vn.payos.type.*;
import com.fourj.orderservice.util.DateTimeUtil;
import com.fourj.orderservice.exception.UnauthorizedAccessException;
import com.fourj.orderservice.dto.OrderStatisticsDto;
import java.math.RoundingMode;
import java.util.HashMap;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.YearMonth;
import java.util.Map;
import java.util.LinkedHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final CartClient cartClient;
    private final ProductClient productClient;
    private final PayOS payOS;
    private final KafkaProducerService kafkaProducerService;
    private final OrderExportService orderExportService;

    @Override
    @Transactional
    public OrderDto createOrder(String userId, String token, CreateOrderRequest request) {
        try {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new EmptyCartException("Danh sách sản phẩm không được để trống");
            }

            for (OrderItemDto item : request.getItems()) {
                ProductDto productInfo = productClient.getProductById(String.valueOf(item.getProductId())).block();
                if (productInfo == null) {
                    throw new ProductNotFoundException("Không tìm thấy sản phẩm: " + item.getProductId());
                }
                if (!productInfo.getPrice().equals(item.getPrice())) {
                    throw new PriceDiscrepancyException("Giá sản phẩm " + item.getProductName() + " đã thay đổi");
                }

                if (productInfo.getStockQuantity() < item.getQuantity()) {
                    throw new InsufficientStockException("Sản phẩm " + item.getProductName() + " không đủ số lượng");
                }
            }

            List<OrderItem> orderItems = request.getItems().stream()
                    .map(item -> OrderItem.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .productImage(null) 
                            .price(item.getPrice())
                            .quantity(item.getQuantity())
                            .subtotal(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .order(null) 
                            .build())
                    .collect(Collectors.toList());

            // Tạo entity Order
            Order order = new Order();
            order.setUserId(userId);
            order.setOrderNumber(generateOrderNumber());
            order.setStatus(OrderStatus.PENDING);
            order.setTotalAmount(calculateTotalAmount(orderItems));
            order.setItems(orderItems);
            order.setShippingAddress(new ShippingAddress(request.getShippingAddress()));
            order.setPaymentInfo(new PaymentInfo(null, PaymentMethod.valueOf(request.getPaymentMethod()), PaymentStatus.PENDING, null, null, null, null, null));
            order.setNotes(request.getNotes());


            // Gán order cho các OrderItem
            orderItems.forEach(item -> item.setOrder(order));

            // Lưu đơn hàng
            Order savedOrder = orderRepository.save(order);
            
            // Upload đơn hàng lên Azure Blob Storage
            try {
                String fileUrl = orderExportService.exportSingleOrder(savedOrder);
                log.info("Đơn hàng #{} đã được upload lên Azure Blob Storage: {}", savedOrder.getOrderNumber(), fileUrl);
            } catch (Exception e) {
                // Chỉ ghi log lỗi, không làm gián đoạn luồng tạo đơn hàng
                log.error("Không thể upload đơn hàng #{} lên Azure: {}", savedOrder.getOrderNumber(), e.getMessage());
            }

            // Gửi sự kiện order created tới Kafka
            try {
                kafkaProducerService.sendOrderCreatedEvent(mapToDto(savedOrder));
            } catch (Exception e) {
                log.error("Lỗi khi gửi sự kiện order created tới Kafka cho order {}: {}", savedOrder.getId(), e.getMessage());
                // Cân nhắc: Có nên rollback transaction hoặc xử lý lỗi gửi Kafka ở đây không?
                // Hiện tại chỉ log lỗi và tiếp tục.
            }

            // Xử lý thanh toán dựa trên paymentMethod
            PaymentMethod paymentMethod = PaymentMethod.valueOf(request.getPaymentMethod());
            if (paymentMethod == PaymentMethod.COD) {
                // COD: Không cần link thanh toán
                savedOrder.setStatus(OrderStatus.PROCESSING);
                savedOrder.getPaymentInfo().setPaymentStatus(PaymentStatus.PENDING);
                orderRepository.save(savedOrder);
            } else {
                // CREDIT_CARD, BANK_TRANSFER, PAYPAL: Sử dụng payOS
                PaymentData paymentData = createPaymentData(savedOrder);
                CheckoutResponseData response = payOS.createPaymentLink(paymentData);

                // Cập nhật PaymentInfo
                PaymentInfo paymentInfo = savedOrder.getPaymentInfo();
                paymentInfo.setPaymentLinkId(response.getPaymentLinkId());
                paymentInfo.setCheckoutUrl(response.getCheckoutUrl());
                paymentInfo.setPayOsOrderCode(response.getOrderCode());
                orderRepository.save(savedOrder);
            }

            // Xóa giỏ hàng
            cartClient.clearCart(token).block();

            // Trả về OrderDto
            return mapToDto(savedOrder);

        } catch (Exception e) {
            log.error("Lỗi khi tạo đơn hàng cho user {}: {}", userId, e.getMessage());
            throw new OrderCreationException("Tạo đơn hàng thất bại: " + e.getMessage(), e);
        }
    }

    private String generateOrderNumber() {
        String orderNumber;
        do {
            orderNumber = generateRandom6Digits();
        } while (orderRepository.findByOrderNumber(orderNumber).isPresent());
        return orderNumber;
    }

    private String generateRandom6Digits() {
        int number = new Random().nextInt(900000) + 100000; // từ 100000 đến 999999
        return String.valueOf(number);
    }


    private BigDecimal calculateTotalAmount(List<OrderItem> items) {
        return items.stream()
                .map(item -> item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private PaymentData createPaymentData(Order order) {
        List<ItemData> items = order.getItems().stream()
                .map(item -> ItemData.builder()
                        .name(item.getProductName())
                        .quantity(item.getQuantity())
                        .price(item.getPrice().intValue())
                        .build())
                .collect(Collectors.toList());

        return PaymentData.builder()
                .orderCode(Long.parseLong(order.getOrderNumber()))
                .amount(order.getTotalAmount().intValue())
//                .amount(10000)
                .description("Đơn hàng #" + order.getOrderNumber())
                .items(items)
                .cancelUrl("http://localhost:5173/payment-result?orderCode=" + order.getOrderNumber() + "&status=cancelled")
                .returnUrl("http://localhost:5173/payment-result?orderCode=" + order.getOrderNumber() + "&status=success")
                .expiredAt((System.currentTimeMillis() / 1000) + 5 * 60)
                .build();
    }

    private OrderDto mapToDto(Order order) {
        return OrderDto.builder()
                .id(order.getId())
                .orderNumber(order.getOrderNumber())
                .userId(order.getUserId())
                .status(order.getStatus())
                .totalAmount(order.getTotalAmount())
                .items(order.getItems().stream().map(this::mapToOrderItemDto).collect(Collectors.toList()))
                .shippingAddress(new ShippingAddressDto(order.getShippingAddress().getAddress()))
                .paymentInfo(mapToPaymentInfoDto(order.getPaymentInfo()))
                .notes(order.getNotes())
                .createdAt(DateTimeUtil.toVietnamDateTime(order.getCreatedAt()))
                .updatedAt(DateTimeUtil.toVietnamDateTime(order.getUpdatedAt()))
                .completedAt(DateTimeUtil.toVietnamDateTime(order.getCompletedAt()))
                .build();
    }

    private OrderItemDto mapToOrderItemDto(OrderItem item) {
        return new OrderItemDto(
                item.getProductId(),
                item.getProductName(),
                item.getPrice(),
                item.getQuantity()
        );
    }

    private PaymentInfoDto mapToPaymentInfoDto(PaymentInfo paymentInfo) {
        return new PaymentInfoDto(
                paymentInfo.getId(),
                paymentInfo.getPaymentMethod(),
                paymentInfo.getPaymentStatus(),
                paymentInfo.getTransactionId(),
                paymentInfo.getPaymentLinkId(),
                paymentInfo.getCheckoutUrl(),
                paymentInfo.getPayOsOrderCode(),
                DateTimeUtil.toVietnamDateTime(paymentInfo.getPaymentDate())
        );
    }

    @Override
    public OrderDto getOrderById(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng: " + id));
        return mapToDto(order);
    }

    @Override
    public Order getOrderByOrderNumber(String orderNumber) {
        return orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng với số: " + orderNumber));
    }

    @Override
    public OrderDto getOrderByNumber(String orderNumber) {
        Order order = orderRepository.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng với số: " + orderNumber));
        return mapToDto(order);
    }

    @Override
    public Page<OrderDto> getOrdersByUserId(String userId, Pageable pageable) {
        return orderRepository.findByUserId(userId, pageable)
                .map(this::mapToDto);
    }

    @Override
    public List<OrderDto> getOrdersByStatus(OrderStatus status) {
        return orderRepository.findByStatus(status)
                .stream()
                .map(this::mapToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderDto updateOrderStatus(Long id, UpdateOrderStatusRequest request) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng: " + id));
        
        // Lưu trạng thái cũ để ghi log
        OrderStatus oldStatus = order.getStatus();
        PaymentStatus oldPaymentStatus = order.getPaymentInfo().getPaymentStatus();
        
        // Kiểm tra luồng trạng thái hợp lệ
        validateStatusTransition(oldStatus, request.getStatus());
        
        // Cập nhật trạng thái đơn hàng
        order.setStatus(request.getStatus());
        
        // Cập nhật note nếu có
        if (request.getNote() != null && !request.getNote().isEmpty()) {
            order.setNotes(request.getNote());
        }
        
        // Đồng bộ PaymentStatus theo OrderStatus
        synchronizePaymentStatus(order, request.getStatus());
        
        // Lưu đơn hàng
        Order savedOrder = orderRepository.save(order);
        
        // Ghi log chi tiết
        log.info("Đã cập nhật đơn hàng #{} từ status={} thành {} và payment từ {} thành {}",
                savedOrder.getOrderNumber(), oldStatus, savedOrder.getStatus(),
                oldPaymentStatus, savedOrder.getPaymentInfo().getPaymentStatus());
        
        return mapToDto(savedOrder);
    }

    // Thêm phương thức để kiểm tra tính hợp lệ của luồng trạng thái
    private void validateStatusTransition(OrderStatus currentStatus, OrderStatus newStatus) {
        // Không cho phép cập nhật từ trạng thái CANCELLED hoặc COMPLETED
        if (currentStatus == OrderStatus.CANCELLED) {
            throw new IllegalStateException("Không thể cập nhật đơn hàng đã hủy");
        }
        
        if (currentStatus == OrderStatus.COMPLETED && newStatus != OrderStatus.REFUNDED) {
            throw new IllegalStateException("Đơn hàng đã hoàn thành chỉ có thể chuyển sang trạng thái hoàn tiền");
        }
        
        // Kiểm tra luồng hợp lệ - có thể mở rộng tùy theo nghiệp vụ
        if (currentStatus == OrderStatus.PENDING && 
            !(newStatus == OrderStatus.PROCESSING || newStatus == OrderStatus.CANCELLED)) {
            throw new IllegalStateException("Đơn hàng đang chờ xử lý chỉ có thể chuyển sang đang xử lý hoặc hủy");
        }
        
        if (currentStatus == OrderStatus.PROCESSING && 
            !(newStatus == OrderStatus.SHIPPED || newStatus == OrderStatus.CANCELLED)) {
            throw new IllegalStateException("Đơn hàng đang xử lý chỉ có thể chuyển sang đang giao hoặc hủy");
        }
        
        if (currentStatus == OrderStatus.SHIPPED && 
            !(newStatus == OrderStatus.DELIVERED || newStatus == OrderStatus.CANCELLED)) {
            throw new IllegalStateException("Đơn hàng đang giao chỉ có thể chuyển sang đã giao hoặc hủy");
        }
        
        if (currentStatus == OrderStatus.DELIVERED && 
            !(newStatus == OrderStatus.COMPLETED || newStatus == OrderStatus.CANCELLED || newStatus == OrderStatus.REFUNDED)) {
            throw new IllegalStateException("Đơn hàng đã giao chỉ có thể chuyển sang hoàn thành, hủy hoặc hoàn tiền");
        }
    }

    // Thêm phương thức đồng bộ PaymentStatus theo OrderStatus
    private void synchronizePaymentStatus(Order order, OrderStatus newStatus) {
        PaymentInfo paymentInfo = order.getPaymentInfo();
        
        switch (newStatus) {
            case PENDING:
                // Đơn hàng chờ xử lý - giữ nguyên trạng thái thanh toán
                break;
            case PROCESSING:
                // Nếu là COD, giữ PaymentStatus là PENDING
                // Nếu là phương thức thanh toán khác và đã thanh toán, giữ là COMPLETED
                if (paymentInfo.getPaymentMethod() != PaymentMethod.COD && 
                    paymentInfo.getPaymentStatus() == PaymentStatus.COMPLETED) {
                    // Giữ nguyên status COMPLETED
                } else if (paymentInfo.getPaymentMethod() == PaymentMethod.COD) {
                    paymentInfo.setPaymentStatus(PaymentStatus.PENDING);
                }
                break;
            case SHIPPED:
            case DELIVERED:
                // Không thay đổi trạng thái thanh toán
                break;
            case COMPLETED:
                // Đơn hàng hoàn thành - đồng bộ với trạng thái thanh toán
                if (paymentInfo.getPaymentMethod() == PaymentMethod.COD) {
                    // Nếu là COD và đơn hoàn thành, cập nhật thanh toán thành COMPLETED
                    paymentInfo.setPaymentComplete();
                }
                break;
            case CANCELLED:
                // Đơn hàng bị hủy - cập nhật trạng thái thanh toán sang CANCELLED
                // Chỉ cập nhật nếu thanh toán đang ở trạng thái PENDING
                if (paymentInfo.getPaymentStatus() == PaymentStatus.PENDING) {
                    paymentInfo.setPaymentStatus(PaymentStatus.CANCELLED);
                }
                break;
            case REFUNDED:
                // Đơn hàng hoàn tiền
                paymentInfo.setPaymentStatus(PaymentStatus.CANCELLED);
                break;
        }
    }

    @Override
    public OrderDto cancelOrder(Long id) {
        Order order = orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng: " + id));
        
        // Nếu đơn hàng có phương thức thanh toán không phải là COD và có paymentLinkId
        // thì cần hủy link thanh toán trên PayOS
        if (order.getPaymentInfo().getPaymentMethod() != PaymentMethod.COD && 
                order.getPaymentInfo().getPaymentLinkId() != null) {
            try {
                // Gọi API hủy link thanh toán trên PayOS
                log.info("Hủy link thanh toán PayOS cho đơn hàng: {}, paymentLinkId: {}", 
                        order.getId(), order.getPaymentInfo().getPaymentLinkId());
                payOS.cancelPaymentLink(Long.parseLong(order.getPaymentInfo().getPaymentLinkId()), "Order cancelled by user");
            } catch (Exception e) {
                log.error("Không thể hủy link thanh toán PayOS: {}", e.getMessage());
                // Vẫn tiếp tục hủy đơn hàng trong hệ thống ngay cả khi không hủy được trên PayOS
            }
        }
        
        order.setStatus(OrderStatus.CANCELLED);
        order.getPaymentInfo().setPaymentStatus(PaymentStatus.CANCELLED);
        order = orderRepository.save(order);
        return mapToDto(order);
    }

    @Override
    public OrderDto createOrderFromEvent(String userId, CreateOrderRequest request) {
        try {
            if (request.getItems() == null || request.getItems().isEmpty()) {
                throw new EmptyCartException("Danh sách sản phẩm không được để trống");
            }

            List<OrderItem> orderItems = request.getItems().stream()
                    .map(item -> OrderItem.builder()
                            .productId(item.getProductId())
                            .productName(item.getProductName())
                            .price(item.getPrice())
                            .quantity(item.getQuantity())
                            .subtotal(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())))
                            .build())
                    .collect(Collectors.toList());

            // Tạo entity Order
            Order order = new Order();
            order.setUserId(userId);
            order.setOrderNumber(generateOrderNumber());
            order.setStatus(OrderStatus.PENDING);
            order.setTotalAmount(calculateTotalAmount(orderItems));
            order.setShippingAddress(new ShippingAddress(request.getShippingAddress()));
            order.setPaymentInfo(new PaymentInfo(null, PaymentMethod.valueOf(request.getPaymentMethod()), PaymentStatus.PENDING, null, null, null, null, null));
            order.setNotes(request.getNotes());

            // Gán order cho các OrderItem
            orderItems.forEach(item -> item.setOrder(order));
            order.setItems(orderItems);

            // Lưu đơn hàng
            Order savedOrder = orderRepository.save(order);
            
            // Upload đơn hàng lên Azure Blob Storage
            try {
                String fileUrl = orderExportService.exportSingleOrder(savedOrder);
                log.info("Đơn hàng #{} từ event đã được upload lên Azure Blob Storage: {}", 
                        savedOrder.getOrderNumber(), fileUrl);
            } catch (Exception e) {
                // Chỉ ghi log lỗi, không làm gián đoạn luồng xử lý
                log.error("Không thể upload đơn hàng #{} lên Azure: {}", savedOrder.getOrderNumber(), e.getMessage());
            }

            // Xử lý thanh toán dựa trên paymentMethod
            PaymentMethod paymentMethod = PaymentMethod.valueOf(request.getPaymentMethod());
            if (paymentMethod == PaymentMethod.COD) {
                // COD: Không cần link thanh toán
                savedOrder.setStatus(OrderStatus.PROCESSING);
                savedOrder.getPaymentInfo().setPaymentStatus(PaymentStatus.PENDING);
                orderRepository.save(savedOrder);
            } else {
                // Tạo thanh toán cho các phương thức khác
                PaymentData paymentData = createPaymentData(savedOrder);
                try {
                    CheckoutResponseData response = payOS.createPaymentLink(paymentData);

                    // Cập nhật PaymentInfo
                    PaymentInfo paymentInfo = savedOrder.getPaymentInfo();
                    paymentInfo.setPaymentLinkId(response.getPaymentLinkId());
                    paymentInfo.setCheckoutUrl(response.getCheckoutUrl());
                    paymentInfo.setPayOsOrderCode(response.getOrderCode());
                    orderRepository.save(savedOrder);
                } catch (Exception e) {
                    log.error("Lỗi khi tạo link thanh toán cho đơn hàng: {}", savedOrder.getId(), e);
                    throw new OrderCreationException("Không thể tạo link thanh toán", e);
                }
            }

            // Trả về OrderDto
            return mapToDto(savedOrder);

        } catch (Exception e) {
            log.error("Lỗi khi tạo đơn hàng từ sự kiện cho user {}: {}", userId, e.getMessage());
            throw new OrderCreationException("Tạo đơn hàng thất bại: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateOrder(WebhookData data) {
        try {
            log.info("Đang cập nhật đơn hàng từ webhook PayOS: {}", data.getOrderCode());
            Order order = getOrderByOrderNumber(data.getOrderCode().toString());

            // Lưu trạng thái cũ để ghi log
            OrderStatus oldStatus = order.getStatus();
            PaymentStatus oldPaymentStatus = order.getPaymentInfo().getPaymentStatus();

            // Cập nhật thông tin thanh toán
            PaymentInfo paymentInfo = order.getPaymentInfo();
            paymentInfo.setTransactionId(data.getReference());

            // Xác định trạng thái từ mã code
            String code = data.getCode();
            log.info("Nhận mã code từ PayOS: {} cho đơn hàng {}", code, data.getOrderCode());

            if ("00".equals(code)) {
                // Thanh toán thành công
                paymentInfo.setPaymentComplete(); // Thiết lập PaymentStatus = COMPLETED và ghi nhận ngày thanh toán

                // Chỉ cập nhật trạng thái đơn hàng nếu đang ở PENDING
                // Tránh cập nhật trạng thái order khi đơn hàng đã chuyển sang trạng thái khác
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.setStatus(OrderStatus.PROCESSING);
                    log.info("Cập nhật trạng thái đơn hàng thành PROCESSING sau khi thanh toán thành công");
                    
                    // Giảm số lượng tồn kho sau khi thanh toán thành công
                    updateProductStock(order);
                } else {
                    log.info("Giữ nguyên trạng thái đơn hàng {} vì đã không còn ở trạng thái PENDING", order.getStatus());
                }

                log.info("Thanh toán thành công cho đơn hàng: {}", order.getOrderNumber());
            } else if ("99".equals(code) || "98".equals(code)) {
                // Thanh toán bị hủy (99) hoặc hết hạn (98)
                paymentInfo.setPaymentStatus(PaymentStatus.CANCELLED);

                // Chỉ cập nhật status của order sang CANCELLED nếu đơn hàng vẫn đang ở PENDING
                if (order.getStatus() == OrderStatus.PENDING) {
                    order.setStatus(OrderStatus.CANCELLED);
                    log.info("Hủy đơn hàng do thanh toán bị hủy/hết hạn");
                } else {
                    log.info("Giữ nguyên trạng thái đơn hàng {} mặc dù thanh toán bị hủy", order.getStatus());
                }

                log.info("Thanh toán bị hủy cho đơn hàng: {}", order.getOrderNumber());
            } else {
                // Các mã khác, log để kiểm tra
                log.warn("Nhận mã code không xử lý: {} cho đơn hàng {}", code, data.getOrderCode());
            }

            // Lưu và ghi log chi tiết
            Order savedOrder = orderRepository.save(order);
            log.info("Đã cập nhật đơn hàng #{} từ status={} thành {} và payment từ {} thành {}",
                    order.getOrderNumber(), oldStatus, savedOrder.getStatus(),
                    oldPaymentStatus, savedOrder.getPaymentInfo().getPaymentStatus());
        } catch (OrderNotFoundException e) {
            log.error("Không tìm thấy đơn hàng từ webhook: {}", data.getOrderCode());
        } catch (Exception e) {
            log.error("Lỗi khi xử lý webhook PayOS: {}", e.getMessage(), e);
        }
    }

    /**
     * Cập nhật số lượng tồn kho cho các sản phẩm trong đơn hàng
     */
    private void updateProductStock(Order order) {
        for (OrderItem item : order.getItems()) {
            productClient.updateStockQuantity(String.valueOf(item.getProductId()), item.getQuantity())
                .subscribe(
                    success -> {
                        if (success) {
                            log.info("Đã giảm số lượng tồn kho cho sản phẩm ID: {}, số lượng: {}", 
                                      item.getProductId(), item.getQuantity());
                        } else {
                            log.error("Không thể giảm số lượng tồn kho cho sản phẩm ID: {}, số lượng: {}", 
                                       item.getProductId(), item.getQuantity());
                        }
                    },
                    error -> log.error("Lỗi khi giảm số lượng tồn kho cho sản phẩm ID: {}: {}", 
                                        item.getProductId(), error.getMessage())
                );
        }
    }

    @Override
    public Page<OrderDto> getOrdersByUserIdAndDateRange(String userId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        log.info("Tìm đơn hàng từ {} đến {} cho user {}", startDate, endDate, userId);
        return orderRepository.findByUserIdAndCreatedAtBetween(userId, startDate, endDate, pageable)
                .map(this::mapToDto);
    }

    @Override
    public OrderDto getOrderByIdAndSellerId(Long orderId, String sellerId) {
        Order order = getOrderOrThrow(orderId);
        
        // Kiểm tra xem đơn hàng có chứa sản phẩm của seller không
        boolean hasAccess = orderRepository.existsByOrderIdAndSellerId(orderId, sellerId);
        if (!hasAccess) {
            throw new UnauthorizedAccessException("Seller không có quyền truy cập đơn hàng này");
        }
        
        return mapToDto(order);
    }

    @Override
    public Page<OrderDto> getOrdersBySellerId(String sellerId, Pageable pageable) {
        return orderRepository.findBySellerId(sellerId, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<OrderDto> getOrdersBySellerIdAndStatus(String sellerId, OrderStatus status, Pageable pageable) {
        return orderRepository.findBySellerIdAndStatus(sellerId, status, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<OrderDto> getOrdersBySellerIdAndDateRange(String sellerId, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return orderRepository.findBySellerIdAndCreatedAtBetween(sellerId, startDate, endDate, pageable)
                .map(this::mapToDto);
    }

    @Override
    public OrderStatisticsDto getSellerOrderStatistics(String sellerId) {
        // Lấy số lượng đơn hàng theo từng trạng thái
        long pendingCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.PENDING);
        long processingCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.PROCESSING);
        long shippedCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.SHIPPED);
        long deliveredCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.DELIVERED);
        long completedCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.COMPLETED);
        long cancelledCount = orderRepository.countBySellerIdAndStatus(sellerId, OrderStatus.CANCELLED);
        
        long totalOrders = pendingCount + processingCount + shippedCount + deliveredCount + completedCount + cancelledCount;
        
        // Tính tổng doanh thu từ các đơn hàng hoàn thành
        BigDecimal totalRevenue = calculateTotalRevenueForSeller(sellerId);
        
        // Tính trung bình giá trị đơn hàng
        BigDecimal avgOrderValue = totalOrders > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        
        // Tính tỷ lệ hoàn thành và hủy
        double completionRate = totalOrders > 0 ? (double) completedCount / totalOrders : 0;
        double cancellationRate = totalOrders > 0 ? (double) cancelledCount / totalOrders : 0;
        
        // Thống kê theo thời gian (7 ngày gần nhất)
        Map<String, Long> orderCountByDay = getSellerOrderCountByDay(sellerId);
        Map<String, BigDecimal> revenueByDay = getSellerRevenueByDay(sellerId);
        
        return OrderStatisticsDto.builder()
                .totalOrders(totalOrders)
                .pendingOrders(pendingCount)
                .processingOrders(processingCount)
                .shippedOrders(shippedCount)
                .deliveredOrders(deliveredCount)
                .completedOrders(completedCount)
                .cancelledOrders(cancelledCount)
                .totalRevenue(totalRevenue)
                .avgOrderValue(avgOrderValue)
                .completionRate(completionRate)
                .cancellationRate(cancellationRate)
                .orderCountByDay(orderCountByDay)
                .revenueByDay(revenueByDay)
                .build();
    }

    @Override
    public Page<OrderDto> getAllOrders(Pageable pageable) {
        return orderRepository.findAll(pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<OrderDto> getOrdersByStatus(OrderStatus status, Pageable pageable) {
        return orderRepository.findByStatus(status, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<OrderDto> getOrdersByDateRange(LocalDateTime startDate, LocalDateTime endDate, Pageable pageable) {
        return orderRepository.findByCreatedAtBetween(startDate, endDate, pageable)
                .map(this::mapToDto);
    }

    @Override
    public OrderStatisticsDto getAdminOrderStatistics() {
        // Lấy số lượng đơn hàng theo từng trạng thái
        long pendingCount = orderRepository.countByStatus(OrderStatus.PENDING);
        long processingCount = orderRepository.countByStatus(OrderStatus.PROCESSING);
        long shippedCount = orderRepository.countByStatus(OrderStatus.SHIPPED);
        long deliveredCount = orderRepository.countByStatus(OrderStatus.DELIVERED);
        long completedCount = orderRepository.countByStatus(OrderStatus.COMPLETED);
        long cancelledCount = orderRepository.countByStatus(OrderStatus.CANCELLED);
        
        long totalOrders = pendingCount + processingCount + shippedCount + deliveredCount + completedCount + cancelledCount;
        
        // Tính tổng doanh thu từ các đơn hàng hoàn thành
        BigDecimal totalRevenue = calculateTotalRevenue();
        
        // Tính trung bình giá trị đơn hàng
        BigDecimal avgOrderValue = totalOrders > 0 ? totalRevenue.divide(BigDecimal.valueOf(totalOrders), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;
        
        // Tính tỷ lệ hoàn thành và hủy
        double completionRate = totalOrders > 0 ? (double) completedCount / totalOrders : 0;
        double cancellationRate = totalOrders > 0 ? (double) cancelledCount / totalOrders : 0;
        
        // Thống kê theo thời gian (7 ngày gần nhất)
        Map<String, Long> orderCountByDay = getOrderCountByDay();
        Map<String, BigDecimal> revenueByDay = getRevenueByDay();
        
        return OrderStatisticsDto.builder()
                .totalOrders(totalOrders)
                .pendingOrders(pendingCount)
                .processingOrders(processingCount)
                .shippedOrders(shippedCount)
                .deliveredOrders(deliveredCount)
                .completedOrders(completedCount)
                .cancelledOrders(cancelledCount)
                .totalRevenue(totalRevenue)
                .avgOrderValue(avgOrderValue)
                .completionRate(completionRate)
                .cancellationRate(cancellationRate)
                .orderCountByDay(orderCountByDay)
                .revenueByDay(revenueByDay)
                .build();
    }

    @Override
    public Map<String, Object> getOrderStatistics() {
        Map<String, Object> statistics = new HashMap<>();
        
        // Đếm số lượng đơn hàng theo trạng thái
        for (OrderStatus status : OrderStatus.values()) {
            long count = orderRepository.countByStatus(status);
            statistics.put(status.name().toLowerCase() + "Orders", count);
        }
        
        // Tổng số đơn hàng
        long totalOrders = orderRepository.count();
        statistics.put("totalOrders", totalOrders);
        
        // Tổng doanh thu
        BigDecimal totalRevenue = calculateTotalRevenue();
        statistics.put("totalRevenue", totalRevenue);
        
        return statistics;
    }

    @Override
    public Map<String, Object> getDashboardStatistics(LocalDateTime startDate, LocalDateTime endDate) {

        // Thêm thống kê cơ bản
        Map<String, Object> statistics = new HashMap<>(getOrderStatistics());
        
        // Thêm dữ liệu thống kê theo thời gian
        Map<String, Long> orderCountByDay = getOrderCountByTimeRange(startDate, endDate);
        Map<String, BigDecimal> revenueByDay = getRevenueByTimeRange(startDate, endDate);
        
        statistics.put("orderCountByDay", orderCountByDay);
        statistics.put("revenueByDay", revenueByDay);
        
        return statistics;
    }

    // Helper methods
    private BigDecimal calculateTotalRevenue() {
        List<Order> completedOrders = orderRepository.findByStatus(OrderStatus.COMPLETED);
        return completedOrders.stream()
                .map(Order::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal calculateTotalRevenueForSeller(String sellerId) {
        // Cần lấy các đơn hàng có chứa sản phẩm của seller và đã hoàn thành
        List<Order> completedOrders = orderRepository.findByStatus(OrderStatus.COMPLETED);
        
        return completedOrders.stream()
                .filter(order -> order.getItems().stream()
                        .anyMatch(item -> sellerId.equals(item.getSellerId())))
                .flatMap(order -> order.getItems().stream()
                        .filter(item -> sellerId.equals(item.getSellerId()))
                        .map(OrderItem::getSubtotal))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private Map<String, Long> getOrderCountByDay() {
        // Lấy 7 ngày gần nhất
        LocalDate today = LocalDate.now();
        Map<String, Long> result = new LinkedHashMap<>();
        
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
            
            long count = orderRepository.findByCreatedAtBetween(startOfDay, endOfDay, Pageable.unpaged()).getTotalElements();
            result.put(date.format(DateTimeFormatter.ISO_DATE), count);
        }
        
        return result;
    }

    private Map<String, BigDecimal> getRevenueByDay() {
        // Lấy 7 ngày gần nhất
        LocalDate today = LocalDate.now();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
            
            Page<Order> ordersOfDay = orderRepository.findByCreatedAtBetween(startOfDay, endOfDay, Pageable.unpaged());
            BigDecimal revenue = ordersOfDay.stream()
                    .map(Order::getTotalAmount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            result.put(date.format(DateTimeFormatter.ISO_DATE), revenue);
        }
        
        return result;
    }

    private Map<String, Long> getSellerOrderCountByDay(String sellerId) {
        // Lấy 7 ngày gần nhất
        LocalDate today = LocalDate.now();
        Map<String, Long> result = new LinkedHashMap<>();
        
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
            
            long count = orderRepository.findBySellerIdAndCreatedAtBetween(
                    sellerId, startOfDay, endOfDay, Pageable.unpaged()).getTotalElements();
            result.put(date.format(DateTimeFormatter.ISO_DATE), count);
        }
        
        return result;
    }

    private Map<String, BigDecimal> getSellerRevenueByDay(String sellerId) {
        // Lấy 7 ngày gần nhất
        LocalDate today = LocalDate.now();
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            LocalDateTime startOfDay = date.atStartOfDay();
            LocalDateTime endOfDay = date.atTime(LocalTime.MAX);
            
            Page<Order> ordersOfDay = orderRepository.findBySellerIdAndCreatedAtBetween(
                    sellerId, startOfDay, endOfDay, Pageable.unpaged());
            
            BigDecimal revenue = ordersOfDay.stream()
                    .flatMap(order -> order.getItems().stream()
                            .filter(item -> sellerId.equals(item.getSellerId()))
                            .map(OrderItem::getSubtotal))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            result.put(date.format(DateTimeFormatter.ISO_DATE), revenue);
        }
        
        return result;
    }

    private Map<String, Long> getOrderCountByTimeRange(LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, Long> result = new LinkedHashMap<>();
        
        // Xác định khoảng thời gian
        long daysBetween = ChronoUnit.DAYS.between(startDate.toLocalDate(), endDate.toLocalDate());
        
        // Nếu khoảng thời gian quá dài, nhóm theo tuần hoặc tháng
        if (daysBetween > 30) {
            // Nhóm theo tháng
            YearMonth start = YearMonth.from(startDate);
            YearMonth end = YearMonth.from(endDate);
            
            while (!start.isAfter(end)) {
                LocalDateTime monthStart = start.atDay(1).atStartOfDay();
                LocalDateTime monthEnd = start.atEndOfMonth().atTime(LocalTime.MAX);
                
                long count = orderRepository.findByCreatedAtBetween(
                        monthStart, monthEnd, Pageable.unpaged()).getTotalElements();
                result.put(start.format(DateTimeFormatter.ofPattern("yyyy-MM")), count);
                
                start = start.plusMonths(1);
            }
        } else {
            // Nhóm theo ngày
            LocalDate start = startDate.toLocalDate();
            LocalDate endLocalDate = endDate.toLocalDate();
            
            while (!start.isAfter(endLocalDate)) {
                LocalDateTime dayStart = start.atStartOfDay();
                LocalDateTime dayEnd = start.atTime(LocalTime.MAX);
                
                long count = orderRepository.findByCreatedAtBetween(
                        dayStart, dayEnd, Pageable.unpaged()).getTotalElements();
                result.put(start.format(DateTimeFormatter.ISO_DATE), count);
                
                start = start.plusDays(1);
            }
        }
        
        return result;
    }

    private Map<String, BigDecimal> getRevenueByTimeRange(LocalDateTime startDate, LocalDateTime endDate) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        
        // Xác định khoảng thời gian
        long daysBetween = ChronoUnit.DAYS.between(startDate.toLocalDate(), endDate.toLocalDate());
        
        // Nếu khoảng thời gian quá dài, nhóm theo tuần hoặc tháng
        if (daysBetween > 30) {
            // Nhóm theo tháng
            YearMonth start = YearMonth.from(startDate);
            YearMonth end = YearMonth.from(endDate);
            
            while (!start.isAfter(end)) {
                LocalDateTime monthStart = start.atDay(1).atStartOfDay();
                LocalDateTime monthEnd = start.atEndOfMonth().atTime(LocalTime.MAX);
                
                Page<Order> ordersOfMonth = orderRepository.findByCreatedAtBetween(
                        monthStart, monthEnd, Pageable.unpaged());
                BigDecimal revenue = ordersOfMonth.stream()
                        .map(Order::getTotalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                result.put(start.format(DateTimeFormatter.ofPattern("yyyy-MM")), revenue);
                
                start = start.plusMonths(1);
            }
        } else {
            // Nhóm theo ngày
            LocalDate start = startDate.toLocalDate();
            LocalDate endLocalDate = endDate.toLocalDate();
            
            while (!start.isAfter(endLocalDate)) {
                LocalDateTime dayStart = start.atStartOfDay();
                LocalDateTime dayEnd = start.atTime(LocalTime.MAX);
                
                Page<Order> ordersOfDay = orderRepository.findByCreatedAtBetween(
                        dayStart, dayEnd, Pageable.unpaged());
                BigDecimal revenue = ordersOfDay.stream()
                        .map(Order::getTotalAmount)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                
                result.put(start.format(DateTimeFormatter.ISO_DATE), revenue);
                
                start = start.plusDays(1);
            }
        }
        
        return result;
    }

    /**
     * Tìm đơn hàng theo ID, ném ngoại lệ nếu không tìm thấy
     * @param id ID của đơn hàng
     * @return Đơn hàng tìm thấy
     * @throws OrderNotFoundException nếu không tìm thấy đơn hàng
     */
    private Order getOrderOrThrow(Long id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new OrderNotFoundException("Không tìm thấy đơn hàng với ID: " + id));
    }
}