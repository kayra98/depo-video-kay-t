package com.kayra.vk.Repository;

import com.kayra.vk.Model.StorageConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StorageConfigRepository extends JpaRepository<StorageConfig, Long> {

    Optional<StorageConfig> findByStorageType(String storageType);
}
