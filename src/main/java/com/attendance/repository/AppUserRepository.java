package com.attendance.repository;

import com.attendance.entity.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsernameIgnoreCase(String username);

    boolean existsByUsernameIgnoreCase(String username);

    List<AppUser> findAllByOrderByUsernameAsc();

    long countBySuperAdminTrueAndEnabledTrue();

    @Query("select u from AppUser u join u.roles r where r.id = :roleId")
    List<AppUser> findByRoleId(@Param("roleId") Long roleId);
}
