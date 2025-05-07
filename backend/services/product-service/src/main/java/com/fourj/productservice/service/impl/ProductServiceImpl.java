package com.fourj.productservice.service.impl;

import com.fourj.productservice.dto.ProductAttributeDto;
import com.fourj.productservice.dto.ProductCreateDto;
import com.fourj.productservice.dto.ProductDto;
import com.fourj.productservice.dto.ProductUpdateDto;
import com.fourj.productservice.event.ProductEventPublisher;
import com.fourj.productservice.exception.ResourceNotFoundException;
import com.fourj.productservice.exception.UnauthorizedAccessException;
import com.fourj.productservice.model.Category;
import com.fourj.productservice.model.Product;
import com.fourj.productservice.model.ProductAttribute;
import com.fourj.productservice.repository.CategoryRepository;
import com.fourj.productservice.repository.ProductAttributeRepository;
import com.fourj.productservice.repository.ProductRepository;
import com.fourj.productservice.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductAttributeRepository attributeRepository;
    private final ProductEventPublisher eventPublisher;

    @Autowired
    public ProductServiceImpl(ProductRepository productRepository,
                              CategoryRepository categoryRepository,
                              ProductAttributeRepository attributeRepository,
                              ProductEventPublisher eventPublisher) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.attributeRepository = attributeRepository;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public ProductDto createProduct(ProductCreateDto productCreateDto) {
        Category category = categoryRepository.findById(productCreateDto.getCategoryId())
                .orElseThrow(() -> new ResourceNotFoundException("Danh mục không tồn tại với id: " + productCreateDto.getCategoryId()));

        Product product = new Product();
        product.setName(productCreateDto.getName());
        product.setDescription(productCreateDto.getDescription());
        product.setPrice(productCreateDto.getPrice());
        product.setStockQuantity(productCreateDto.getStockQuantity());
        product.setImageUrl(productCreateDto.getImageUrl());
        product.setCategory(category);
        product.setActive(true);
        
        // Gán sellerId nếu có
        if (productCreateDto.getSellerId() != null) {
            product.setSellerId(productCreateDto.getSellerId());
        }

        Product savedProduct = productRepository.save(product);

        // Lưu các thuộc tính sản phẩm
        if (productCreateDto.getAttributes() != null && !productCreateDto.getAttributes().isEmpty()) {
            for (ProductAttributeDto attributeDto : productCreateDto.getAttributes()) {
                ProductAttribute attribute = new ProductAttribute();
                attribute.setProduct(savedProduct);
                attribute.setName(attributeDto.getName());
                attribute.setValue(attributeDto.getValue());
                attributeRepository.save(attribute);
            }
        }

        ProductDto productDto = mapToDto(productRepository.findById(savedProduct.getId()).orElseThrow());
        
        // Phát sự kiện sản phẩm được tạo
        try {
            eventPublisher.publishProductCreated(productDto);
            log.info("Published product created event for product ID: {}", productDto.getId());
        } catch (Exception e) {
            log.error("Failed to publish product created event for ID: {}", productDto.getId(), e);
            // Không ảnh hưởng đến transaction chính
        }
        
        return productDto;
    }

    @Override
    public ProductDto getProductById(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại với id: " + id));
        return mapToDto(product);
    }

    @Override
    public Page<ProductDto> getAllProducts(Pageable pageable) {
        return productRepository.findByActiveTrue(pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<ProductDto> getProductsByCategory(Long categoryId, Pageable pageable) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Danh mục không tồn tại với id: " + categoryId);
        }
        return productRepository.findByCategoryIdAndActiveTrue(categoryId, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<ProductDto> searchProducts(String keyword, Pageable pageable) {
        return productRepository.findByNameContainingIgnoreCaseAndActiveTrue(keyword, pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional
    public ProductDto updateProduct(Long id, ProductUpdateDto productUpdateDto) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại với id: " + id));

        if (productUpdateDto.getName() != null) {
            product.setName(productUpdateDto.getName());
        }
        if (productUpdateDto.getDescription() != null) {
            product.setDescription(productUpdateDto.getDescription());
        }
        if (productUpdateDto.getPrice() != null) {
            product.setPrice(productUpdateDto.getPrice());
        }
        if (productUpdateDto.getStockQuantity() != null) {
            product.setStockQuantity(productUpdateDto.getStockQuantity());
        }
        if (productUpdateDto.getImageUrl() != null) {
            product.setImageUrl(productUpdateDto.getImageUrl());
        }
        if (productUpdateDto.getCategoryId() != null) {
            Category category = categoryRepository.findById(productUpdateDto.getCategoryId())
                    .orElseThrow(() -> new ResourceNotFoundException("Danh mục không tồn tại với id: " + productUpdateDto.getCategoryId()));
            product.setCategory(category);
        }
        if (productUpdateDto.getActive() != null) {
            product.setActive(productUpdateDto.getActive());
        }

        // Lưu sản phẩm trước khi xử lý thuộc tính để tránh ConcurrentModificationException
        Product updatedProduct = productRepository.save(product);

        // Cập nhật thuộc tính nếu được cung cấp
        if (productUpdateDto.getAttributes() != null) {
            // Lấy danh sách thuộc tính cũ để xóa sau
            List<ProductAttribute> oldAttributes = attributeRepository.findByProductId(product.getId());
            
            // Xóa tất cả các thuộc tính cũ khỏi product.attributes 
            // để tránh ConcurrentModificationException
            product.setAttributes(new HashSet<>());
            
            // Lưu product với danh sách thuộc tính trống
            productRepository.save(product);
            
            // Xóa các thuộc tính cũ trong database
            attributeRepository.deleteAll(oldAttributes);
            
            // Tạo và lưu các thuộc tính mới
            Set<ProductAttribute> newAttributes = new HashSet<>();
            for (ProductAttributeDto attributeDto : productUpdateDto.getAttributes()) {
                ProductAttribute attribute = new ProductAttribute();
                attribute.setProduct(product);
                attribute.setName(attributeDto.getName());
                attribute.setValue(attributeDto.getValue());
                newAttributes.add(attributeRepository.save(attribute));
            }
            
            // Gán lại danh sách thuộc tính mới
            product.setAttributes(newAttributes);
            updatedProduct = productRepository.save(product);
        }

        ProductDto productDto = mapToDto(updatedProduct);
        
        // Phát sự kiện sản phẩm được cập nhật
        try {
            eventPublisher.publishProductUpdated(productDto);
            log.info("Published product updated event for product ID: {}", productDto.getId());
        } catch (Exception e) {
            log.error("Failed to publish product updated event for ID: {}", productDto.getId(), e);
        }
        
        return productDto;
    }

    @Override
    @Transactional
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResourceNotFoundException("Sản phẩm không tồn tại với id: " + id);
        }
        
        try {
            // Phát sự kiện sản phẩm bị xóa
            ProductDto productDto = getProductById(id);
            productRepository.deleteById(id);
            eventPublisher.publishProductDeleted(productDto);
            log.info("Published product deleted event for product ID: {}", id);
        } catch (Exception e) {
            log.error("Error processing product deletion for ID: {}", id, e);
            throw e;
        }
    }

    private ProductDto mapToDto(Product product) {
        ProductDto.ProductDtoBuilder builder = ProductDto.builder()
                .id(product.getId())
                .name(product.getName())
                .description(product.getDescription())
                .price(product.getPrice())
                .stockQuantity(product.getStockQuantity())
                .imageUrl(product.getImageUrl())
                .active(product.isActive())
                .sellerId(product.getSellerId())
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt());
        
        // Xử lý an toàn khi category có thể null
        if (product.getCategory() != null) {
            builder.categoryId(product.getCategory().getId())
                   .categoryName(product.getCategory().getName());
        }
        
        ProductDto dto = builder.build();

        // Xử lý attributes nếu có
        Set<ProductAttribute> attributes = product.getAttributes();
        if (attributes != null && !attributes.isEmpty()) {
            // Sử dụng new ArrayList để tránh ConcurrentModificationException
            dto.setAttributes(new ArrayList<>(attributes).stream()
                    .map(attr -> new ProductAttributeDto(attr.getId(), attr.getName(), attr.getValue()))
                    .collect(Collectors.toList()));
        }

        return dto;
    }
    
    // Các phương thức mới

    @Override
    public Page<ProductDto> getProductsBySeller(String sellerId, Pageable pageable) {
        return productRepository.findBySellerIdAndActiveTrue(sellerId, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<ProductDto> searchProductsBySeller(String sellerId, String keyword, Pageable pageable) {
        return productRepository.findBySellerIdAndNameContainingIgnoreCaseAndActiveTrue(sellerId, keyword, pageable)
                .map(this::mapToDto);
    }

    @Override
    public ProductDto getProductByIdAndSellerId(Long id, String sellerId) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại với id: " + id));
        
        if (!product.getSellerId().equals(sellerId)) {
            throw new UnauthorizedAccessException("Bạn không có quyền truy cập sản phẩm này");
        }
        
        return mapToDto(product);
    }

    @Override
    public Page<ProductDto> getProductsByActiveStatus(boolean active, Pageable pageable) {
        return productRepository.findByActive(active, pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<ProductDto> getAllProductsIncludeInactive(Pageable pageable) {
        return productRepository.findAll(pageable)
                .map(this::mapToDto);
    }

    @Override
    public Page<ProductDto> getProductsByCategoryAndSeller(Long categoryId, String sellerId, Pageable pageable) {
        if (!categoryRepository.existsById(categoryId)) {
            throw new ResourceNotFoundException("Danh mục không tồn tại với id: " + categoryId);
        }
        return productRepository.findByCategoryIdAndSellerIdAndActiveTrue(categoryId, sellerId, pageable)
                .map(this::mapToDto);
    }

    @Override
    @Transactional
    public boolean updateStockQuantity(Long productId, int quantity) {
        try {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResourceNotFoundException("Sản phẩm không tồn tại với id: " + productId));
            
            int currentStock = product.getStockQuantity();
            
            if (currentStock < quantity) {
                log.error("Không đủ số lượng sản phẩm {} trong kho. Yêu cầu: {}, Hiện có: {}", 
                          productId, quantity, currentStock);
                return false;
            }
            
            product.setStockQuantity(currentStock - quantity);
            productRepository.save(product);
            
            // Phát sự kiện sản phẩm được cập nhật
            try {
                ProductDto productDto = mapToDto(product);
                eventPublisher.publishProductUpdated(productDto);
                log.info("Đã cập nhật số lượng tồn kho của sản phẩm ID: {}, giảm: {}, còn lại: {}", 
                         productId, quantity, product.getStockQuantity());
            } catch (Exception e) {
                log.error("Không thể phát sự kiện cập nhật sản phẩm cho ID: {}", productId, e);
            }
            
            return true;
        } catch (ResourceNotFoundException e) {
            log.error("Không tìm thấy sản phẩm khi cập nhật số lượng tồn kho: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Lỗi khi cập nhật số lượng tồn kho cho sản phẩm {}: {}", productId, e.getMessage());
            return false;
        }
    }
}