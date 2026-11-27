package com.attendance.security;

/**
 * What a user may do inside a module.
 * <ul>
 *     <li>VIEW   – read data</li>
 *     <li>MODIFY – create and edit data</li>
 *     <li>DELETE – remove data</li>
 *     <li>GRANT  – give this module's permissions (up to what the user holds) to other users</li>
 * </ul>
 * MODIFY, DELETE and GRANT always include VIEW.
 */
public enum PermissionAction {
    VIEW,
    MODIFY,
    DELETE,
    GRANT;

    public String messageKey() {
        return "permission.action." + name();
    }
}
