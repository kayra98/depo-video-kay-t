package com.kayra.vk.Repository;

import com.kayra.vk.Model.RecordList;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecordListRepository extends JpaRepository<RecordList, Long> {

    List<RecordList> findAllByOrderByCreatedAtDesc();

    List<RecordList> findByOrderNo(String orderNo);

    List<RecordList> findByS3UploadedFalse();

    List<RecordList> findByCreatedAtBefore(LocalDateTime dateTime);

    long countByS3UploadedTrue();

    Page<RecordList> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<RecordList> findByOrderNoContainingIgnoreCaseOrderByCreatedAtDesc(
            String orderNo, Pageable pageable);
}
