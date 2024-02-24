package pool.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pool.dataobject.SftpConfig;

@Repository
public interface SftpConfigRepository extends JpaRepository<SftpConfig, Long> {
}