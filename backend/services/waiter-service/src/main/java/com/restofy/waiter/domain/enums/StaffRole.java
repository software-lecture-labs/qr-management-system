package com.restofy.waiter.domain.enums;

/**
 * Staff roles proposed in docs/database.md (staff_memberships.role). The real role names and permission
 * matrix are approved in the Auth work; WAITER-01 only grants the WAITER role.
 */
public enum StaffRole {
    ADMIN,
    WAITER,
    KITCHEN
}
