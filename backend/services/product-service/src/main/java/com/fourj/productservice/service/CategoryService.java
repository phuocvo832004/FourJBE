package com.fourj.productservice.service;

import com.fourj.productservice.dto.CategoryCreateDto;
import com.fourj.productservice.dto.CategoryDto;
import com.fourj.productservice.model.Category;

import java.util.List;

public interface CategoryService {
    CategoryDto createCategory(CategoryCreateDto categoryCreateDto);
    CategoryDto getCategoryById(Long id);
    List<CategoryDto> getAllCategories();
    CategoryDto updateCategory(Long id, CategoryCreateDto categoryUpdateDto);
    void deleteCategory(Long id);
}