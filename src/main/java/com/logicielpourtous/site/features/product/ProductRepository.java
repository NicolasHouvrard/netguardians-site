package com.logicielpourtous.site.features.product;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import java.util.List;
import java.util.Optional;

public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {
    List<Product> findByActiveTrueOrderByNameAsc();
    List<Product> findByTypeOrderByNameAsc(Product.ProductType type);
    List<Product> findByNameContainingIgnoreCaseAndActiveTrue(String name);
    List<Product> findTop20ByActiveTrueAndNameContainingIgnoreCaseOrderByNameAsc(String name);
    List<Product> findAllByOrderByNameAsc();
    List<Product> findByCategoryIdOrderByNameAsc(Long categoryId);
    List<Product> findByTypeAndActiveTrueOrderByNameAsc(Product.ProductType type);
    long countByActiveTrue();
    long countByType(Product.ProductType type);
    long countByPhotoPathIsNull();
    Optional<Product> findBySkuIgnoreCase(String sku);
    Optional<Product> findByReferenceIgnoreCase(String reference);
}
