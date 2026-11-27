package com.attendance.repository;

import com.attendance.entity.AppRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppRoleRepository extends JpaRepository<AppRole, Long> {

    Optional<AppRole> findByNameIgnoreCase(String name);

    List<AppRole> findAllByOrderByNameAsc();

    /** Remove a deleted employee group from every role grant (otherwise the FK blocks the delete). */
    @Modifying
    @Query(value = "delete from app_role_grant_groups where group_id = :groupId", nativeQuery = true)
    int removeGroupFromAllGrants(@Param("groupId") Long groupId);
}
