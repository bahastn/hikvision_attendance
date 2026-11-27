// Users, roles and audit log administration pages.
// Every value coming from the server is HTML-escaped before it is put in the page.
const SecurityAdmin = (() => {
    const t = (key, fallback) => (window.securityI18n && window.securityI18n[key]) || fallback || key;

    const esc = (value) => String(value ?? '')
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#39;');

    /** Whether the signed-in user holds an action on a module (UI hint only; the server decides). */
    const can = (module, action) => {
        const user = window.currentUser || {};
        if (user.superAdmin) return true;
        return !!(user.permissions && user.permissions[module] && user.permissions[module][action]);
    };

    async function api(method, url, body) {
        const options = { method, headers: { 'Content-Type': 'application/json' } };
        if (body !== undefined) options.body = JSON.stringify(body);
        const response = await fetch(url, options);
        let json;
        try {
            json = await response.json();
        } catch (e) {
            json = { success: false, message: t('failed') };
        }
        return json;
    }

    function toast(message, type) {
        if (window.app && app.showToast) app.showToast(message, type);
    }

    function formatDate(value) {
        if (!value) return t('never');
        const d = new Date(value);
        return isNaN(d) ? esc(value) : esc(d.toLocaleString());
    }

    function openModal(id) {
        document.getElementById(id)?.classList.add('active');
    }

    // =====================================================================
    // Users page
    // =====================================================================
    let users = [];
    let roles = [];

    async function initUsersPage() {
        document.getElementById('btn-add-user')?.addEventListener('click', () => openUserModal(null));
        document.getElementById('user-form')?.addEventListener('submit', (e) => { e.preventDefault(); saveUser(); });
        document.getElementById('reset-form')?.addEventListener('submit', (e) => { e.preventDefault(); resetPassword(); });
        document.getElementById('btn-refresh-audit')?.addEventListener('click', loadAudit);
        document.getElementById('user-search')?.addEventListener('input', (e) => filterRows('users-table-body', e.target.value));
        await Promise.all([loadRoles(), loadUsers()]);
        loadAudit();
    }

    async function loadRoles() {
        const res = await api('GET', '/api/admin/roles');
        roles = res.success ? res.data : [];
    }

    async function loadUsers() {
        const res = await api('GET', '/api/admin/users');
        const tbody = document.getElementById('users-table-body');
        if (!res.success) {
            tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;padding:30px;">${esc(res.message)}</td></tr>`;
            return;
        }
        users = res.data;
        if (users.length === 0) {
            tbody.innerHTML = `<tr><td colspan="6" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${esc(t('noUsers'))}</td></tr>`;
            return;
        }
        const canModify = can('USER_MANAGEMENT', 'MODIFY');
        const canDelete = can('USER_MANAGEMENT', 'DELETE');
        tbody.innerHTML = users.map(u => {
            const badges = [];
            badges.push(u.enabled
                ? `<span class="badge badge-present">${esc(t('enabled'))}</span>`
                : `<span class="badge badge-absent">${esc(t('disabled'))}</span>`);
            if (u.superAdmin) badges.push(`<span class="badge badge-leave">${esc(t('superAdmin'))}</span>`);
            if (u.locked) badges.push(`<span class="badge badge-late">${esc(t('locked'))}</span>`);
            if (u.mustChangePassword) badges.push(`<span class="badge badge-unknown">${esc(t('mustChange'))}</span>`);

            let actions = '';
            if (u.manageable) {
                if (canModify) {
                    actions += `<button class="btn btn-sm btn-secondary btn-icon" data-action="edit" data-id="${u.id}" title="${esc(t('editLabel'))}"><i class="fas fa-edit"></i></button> `;
                    actions += `<button class="btn btn-sm btn-secondary btn-icon" data-action="reset" data-id="${u.id}" title="${esc(t('resetPassword'))}"><i class="fas fa-key"></i></button> `;
                    if (u.locked) {
                        actions += `<button class="btn btn-sm btn-success btn-icon" data-action="unlock" data-id="${u.id}" title="${esc(t('unlock'))}"><i class="fas fa-unlock"></i></button> `;
                    }
                }
                if (canDelete) {
                    actions += `<button class="btn btn-sm btn-secondary btn-icon" data-action="delete" data-id="${u.id}" title="${esc(t('deleteLabel'))}"><i class="fas fa-trash"></i></button>`;
                }
            } else {
                actions = `<span title="${esc(t('notManageable'))}" style="color:var(--hk-text-muted);"><i class="fas fa-lock"></i></span>`;
            }

            return `<tr data-search="${esc((u.username + ' ' + (u.fullName || '') + ' ' + (u.roleNames || []).join(' ')).toLowerCase())}">
                <td>${esc(u.username)}</td>
                <td>${esc(u.fullName || '-')}</td>
                <td>${(u.roleNames || []).map(n => `<span class="badge badge-leave">${esc(n)}</span>`).join(' ') || '-'}</td>
                <td>${badges.join(' ')}</td>
                <td>${formatDate(u.lastLoginAt)}</td>
                <td>${actions}</td>
            </tr>`;
        }).join('');

        tbody.querySelectorAll('button[data-action]').forEach(btn => {
            const id = Number(btn.dataset.id);
            const user = users.find(x => x.id === id);
            btn.addEventListener('click', () => {
                if (btn.dataset.action === 'edit') openUserModal(user);
                if (btn.dataset.action === 'reset') openResetModal(user);
                if (btn.dataset.action === 'unlock') unlockUser(user);
                if (btn.dataset.action === 'delete') deleteUser(user);
            });
        });
    }

    function openUserModal(user) {
        document.getElementById('user-modal-title').textContent = user ? t('edit') : t('add');
        document.getElementById('user-id').value = user ? user.id : '';
        const username = document.getElementById('user-username');
        username.value = user ? user.username : '';
        username.readOnly = !!user;
        document.getElementById('user-fullname').value = user?.fullName || '';
        document.getElementById('user-email').value = user?.email || '';
        document.getElementById('user-enabled').checked = user ? user.enabled : true;
        const superBox = document.getElementById('user-superadmin');
        if (superBox) superBox.checked = !!user?.superAdmin;
        document.getElementById('user-password').value = '';
        document.getElementById('user-password-group').style.display = user ? 'none' : '';

        const selected = new Set(user?.roleIds || []);
        const assignable = roles.filter(r => r.manageable || selected.has(r.id));
        const list = document.getElementById('user-roles-list');
        list.innerHTML = assignable.length === 0
            ? `<span style="color:var(--hk-text-muted);">${esc(t('noRolesToAssign'))}</span>`
            : assignable.map(r => `
                <label style="display:flex;align-items:flex-start;gap:8px;padding:4px 0;">
                    <input type="checkbox" class="user-role-check" value="${r.id}" ${selected.has(r.id) ? 'checked' : ''} ${r.manageable ? '' : 'disabled'}>
                    <span><strong>${esc(r.name)}</strong>${r.description ? `<br><small style="color:var(--hk-text-muted);">${esc(r.description)}</small>` : ''}</span>
                </label>`).join('');
        openModal('user-modal');
    }

    async function saveUser() {
        const id = document.getElementById('user-id').value;
        const existing = id ? users.find(u => u.id === Number(id)) : null;
        const superBox = document.getElementById('user-superadmin');
        const body = {
            username: document.getElementById('user-username').value.trim(),
            fullName: document.getElementById('user-fullname').value.trim(),
            email: document.getElementById('user-email').value.trim() || null,
            enabled: document.getElementById('user-enabled').checked,
            superAdmin: superBox ? superBox.checked : !!existing?.superAdmin,
            roleIds: Array.from(document.querySelectorAll('.user-role-check:checked')).map(c => Number(c.value))
        };
        if (!id) {
            body.password = document.getElementById('user-password').value;
            if (!body.password) {
                toast(t('passwordRequired'), 'error');
                return;
            }
        }
        const res = id ? await api('PUT', `/api/admin/users/${id}`, body) : await api('POST', '/api/admin/users', body);
        if (res.success) {
            toast(t('saved'), 'success');
            app.closeModal('user-modal');
            await loadUsers();
            loadAudit();
        } else {
            toast(res.message || t('failed'), 'error');
        }
    }

    function openResetModal(user) {
        document.getElementById('reset-user-id').value = user.id;
        document.getElementById('reset-user-name').textContent = user.username;
        document.getElementById('reset-password').value = '';
        openModal('reset-modal');
    }

    async function resetPassword() {
        const id = document.getElementById('reset-user-id').value;
        const res = await api('POST', `/api/admin/users/${id}/reset-password`,
            { newPassword: document.getElementById('reset-password').value });
        if (res.success) {
            toast(t('passwordReset'), 'success');
            app.closeModal('reset-modal');
            await loadUsers();
            loadAudit();
        } else {
            toast(res.message || t('failed'), 'error');
        }
    }

    async function unlockUser(user) {
        const res = await api('POST', `/api/admin/users/${user.id}/unlock`);
        toast(res.success ? t('unlocked') : (res.message || t('failed')), res.success ? 'success' : 'error');
        if (res.success) { await loadUsers(); loadAudit(); }
    }

    async function deleteUser(user) {
        if (!confirm(`${t('deleteUserConfirm')}\n${user.username}`)) return;
        const res = await api('DELETE', `/api/admin/users/${user.id}`);
        toast(res.success ? t('deleted') : (res.message || t('failed')), res.success ? 'success' : 'error');
        if (res.success) { await loadUsers(); loadAudit(); }
    }

    async function loadAudit() {
        const tbody = document.getElementById('audit-table-body');
        if (!tbody) return;
        const res = await api('GET', '/api/admin/audit?limit=200');
        if (!res.success) {
            tbody.innerHTML = `<tr><td colspan="6">${esc(res.message)}</td></tr>`;
            return;
        }
        tbody.innerHTML = res.data.map(a => `
            <tr>
                <td style="white-space:nowrap;">${formatDate(a.eventTime)}</td>
                <td>${esc(a.actor || '-')}</td>
                <td><span class="badge ${a.success ? 'badge-present' : 'badge-absent'}">${esc(a.action)}</span></td>
                <td>${esc(a.target || '-')}</td>
                <td style="max-width:420px;word-break:break-word;font-size:12px;">${esc(a.details || '')}</td>
                <td>${esc(a.ipAddress || '-')}</td>
            </tr>`).join('');
    }

    function filterRows(tbodyId, query) {
        const q = (query || '').toLowerCase();
        document.querySelectorAll(`#${tbodyId} tr[data-search]`).forEach(row => {
            row.style.display = row.dataset.search.includes(q) ? '' : 'none';
        });
    }

    // =====================================================================
    // Roles page
    // =====================================================================
    let modules = [];
    let groups = [];
    let roleList = [];

    async function initRolesPage() {
        document.getElementById('btn-add-role')?.addEventListener('click', () => openRoleModal(null));
        document.getElementById('btn-add-grant')?.addEventListener('click', () => addGrantRow(null));
        document.getElementById('role-form')?.addEventListener('submit', (e) => { e.preventDefault(); saveRole(); });

        const [modRes, groupRes] = await Promise.all([api('GET', '/api/admin/modules'), api('GET', '/api/groups')]);
        modules = modRes.success ? modRes.data : [];
        groups = groupRes.success ? groupRes.data : [];
        await loadRoleTable();
    }

    const moduleLabel = (key) => (modules.find(m => m.module === key) || {}).label || key;
    const groupName = (id) => (groups.find(g => g.id === id) || {}).name || `#${id}`;

    async function loadRoleTable() {
        const res = await api('GET', '/api/admin/roles');
        const tbody = document.getElementById('roles-table-body');
        if (!res.success) {
            tbody.innerHTML = `<tr><td colspan="4" style="text-align:center;padding:30px;">${esc(res.message)}</td></tr>`;
            return;
        }
        roleList = res.data;
        if (roleList.length === 0) {
            tbody.innerHTML = `<tr><td colspan="4" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${esc(t('noRoles'))}</td></tr>`;
            return;
        }
        const canModify = can('USER_MANAGEMENT', 'MODIFY');
        const canDelete = can('USER_MANAGEMENT', 'DELETE');
        tbody.innerHTML = roleList.map(r => {
            const summary = r.grants.length === 0
                ? `<span style="color:var(--hk-text-muted);">${esc(t('noPermissions'))}</span>`
                : r.grants.map(g => {
                    const actions = g.actions.map(a => esc(t('action' + a, a))).join(', ');
                    const mod = modules.find(m => m.module === g.module);
                    const scope = mod && !mod.groupScoped
                        ? t('orgWide')
                        : (g.allGroups ? t('allGroups') : (g.groupNames || []).join(', '));
                    return `<div style="margin-bottom:3px;"><strong>${esc(moduleLabel(g.module))}</strong>: ${actions}
                            <span style="color:var(--hk-text-muted);">— ${esc(scope)}</span></div>`;
                }).join('');
            let actions = '';
            if (r.manageable) {
                if (canModify) actions += `<button class="btn btn-sm btn-secondary btn-icon" data-action="edit" data-id="${r.id}" title="${esc(t('editLabel'))}"><i class="fas fa-edit"></i></button> `;
                if (canDelete) actions += `<button class="btn btn-sm btn-secondary btn-icon" data-action="delete" data-id="${r.id}" title="${esc(t('deleteLabel'))}"><i class="fas fa-trash"></i></button>`;
            } else {
                actions = `<span title="${esc(t('notManageable'))}" style="color:var(--hk-text-muted);"><i class="fas fa-lock"></i></span>`;
            }
            return `<tr>
                <td><strong>${esc(r.name)}</strong>${r.description ? `<br><small style="color:var(--hk-text-muted);">${esc(r.description)}</small>` : ''}</td>
                <td style="font-size:13px;">${summary}</td>
                <td>${r.userCount}</td>
                <td style="white-space:nowrap;">${actions}</td>
            </tr>`;
        }).join('');

        tbody.querySelectorAll('button[data-action]').forEach(btn => {
            const role = roleList.find(x => x.id === Number(btn.dataset.id));
            btn.addEventListener('click', () => {
                if (btn.dataset.action === 'edit') openRoleModal(role);
                if (btn.dataset.action === 'delete') deleteRole(role);
            });
        });
    }

    function openRoleModal(role) {
        document.getElementById('role-modal-title').textContent = role ? t('editRole') : t('addRole');
        document.getElementById('role-id').value = role ? role.id : '';
        document.getElementById('role-name').value = role?.name || '';
        document.getElementById('role-description').value = role?.description || '';
        document.getElementById('grant-rows').innerHTML = '';
        (role?.grants || []).forEach(g => addGrantRow(g));
        if (!role) addGrantRow(null);
        openModal('role-modal');
    }

    /** Modules the signed-in user may put into a role (plus the one already on an existing row). */
    function grantableModules(current) {
        return modules.filter(m => m.grantableActions.length > 0 || m.module === current);
    }

    function addGrantRow(grant) {
        const container = document.getElementById('grant-rows');
        const row = document.createElement('div');
        row.className = 'grant-row';
        row.style.cssText = 'border:1px solid var(--hk-border);border-radius:6px;padding:10px;';
        const options = grantableModules(grant?.module)
            .map(m => `<option value="${esc(m.module)}" ${grant?.module === m.module ? 'selected' : ''}>${esc(m.label)}</option>`)
            .join('');
        row.innerHTML = `
            <div style="display:flex;gap:10px;align-items:flex-end;flex-wrap:wrap;">
                <div style="flex:1;min-width:200px;">
                    <label class="form-label">${esc(t('module'))}</label>
                    <select class="form-control grant-module">${options}</select>
                </div>
                <button type="button" class="btn btn-sm btn-secondary grant-remove" title="${esc(t('remove'))}"><i class="fas fa-times"></i></button>
            </div>
            <div class="grant-details" style="margin-top:8px;"></div>`;
        container.appendChild(row);

        const select = row.querySelector('.grant-module');
        const render = (initial) => renderGrantDetails(row, select.value, initial);
        select.addEventListener('change', () => render(null));
        row.querySelector('.grant-remove').addEventListener('click', () => row.remove());
        render(grant);
    }

    function renderGrantDetails(row, moduleKey, grant) {
        const mod = modules.find(m => m.module === moduleKey);
        const details = row.querySelector('.grant-details');
        if (!mod) { details.innerHTML = ''; return; }

        const selectedActions = new Set(grant?.actions || ['VIEW']);
        const actionsHtml = ['VIEW', 'MODIFY', 'DELETE', 'GRANT'].map(a => {
            const allowed = mod.grantableActions.includes(a);
            return `<label style="display:inline-flex;align-items:center;gap:5px;margin-inline-end:14px;${allowed ? '' : 'opacity:.45;'}">
                <input type="checkbox" class="grant-action" value="${a}" ${selectedActions.has(a) && allowed ? 'checked' : ''} ${allowed ? '' : 'disabled'}>
                ${esc(t('action' + a, a))}</label>`;
        }).join('');

        let scopeHtml;
        if (!mod.groupScoped) {
            scopeHtml = `<span style="color:var(--hk-text-muted);font-size:12px;"><i class="fas fa-globe"></i> ${esc(t('orgWide'))}</span>`;
        } else {
            const grantGroups = mod.grantableAllGroups ? groups : groups.filter(g => mod.grantableGroupIds.includes(g.id));
            const allChecked = grant ? grant.allGroups : mod.grantableAllGroups;
            const chosen = new Set(grant?.groupIds || []);
            const radioName = 'scope-' + Math.random().toString(36).slice(2);
            scopeHtml = `
                <label style="display:inline-flex;align-items:center;gap:5px;margin-inline-end:14px;${mod.grantableAllGroups ? '' : 'opacity:.45;'}">
                    <input type="radio" name="${radioName}" class="grant-scope-all" ${allChecked ? 'checked' : ''} ${mod.grantableAllGroups ? '' : 'disabled'}> ${esc(t('allGroups'))}</label>
                <label style="display:inline-flex;align-items:center;gap:5px;">
                    <input type="radio" name="${radioName}" class="grant-scope-some" ${allChecked ? '' : 'checked'}> ${esc(t('selectGroups'))}</label>
                <div class="grant-group-list" style="margin-top:6px;display:${allChecked ? 'none' : 'flex'};flex-wrap:wrap;gap:6px 14px;">
                    ${grantGroups.length === 0 ? `<span style="color:var(--hk-text-muted);">${esc(t('noGroupsAvailable'))}</span>` :
                        grantGroups.map(g => `<label style="display:inline-flex;align-items:center;gap:5px;">
                            <input type="checkbox" class="grant-group" value="${g.id}" ${chosen.has(g.id) ? 'checked' : ''}> ${esc(g.name)}</label>`).join('')}
                </div>`;
        }

        details.innerHTML = `
            <div style="display:flex;gap:24px;flex-wrap:wrap;">
                <div><div class="form-label">${esc(t('actions'))}</div>${actionsHtml}</div>
                <div style="flex:1;min-width:240px;"><div class="form-label">${esc(t('groups'))}</div>${scopeHtml}</div>
            </div>`;

        const list = details.querySelector('.grant-group-list');
        details.querySelector('.grant-scope-all')?.addEventListener('change', () => { if (list) list.style.display = 'none'; });
        details.querySelector('.grant-scope-some')?.addEventListener('change', () => { if (list) list.style.display = 'flex'; });
    }

    async function saveRole() {
        const id = document.getElementById('role-id').value;
        const grants = Array.from(document.querySelectorAll('#grant-rows .grant-row')).map(row => {
            const module = row.querySelector('.grant-module').value;
            const actions = Array.from(row.querySelectorAll('.grant-action:checked')).map(c => c.value);
            const allBox = row.querySelector('.grant-scope-all');
            const allGroups = allBox ? allBox.checked : true;
            const groupIds = Array.from(row.querySelectorAll('.grant-group:checked')).map(c => Number(c.value));
            return { module, actions, allGroups, groupIds };
        });
        if (grants.length === 0) {
            toast(t('needOnePermission'), 'error');
            return;
        }
        const body = {
            name: document.getElementById('role-name').value.trim(),
            description: document.getElementById('role-description').value.trim(),
            grants
        };
        const res = id ? await api('PUT', `/api/admin/roles/${id}`, body) : await api('POST', '/api/admin/roles', body);
        if (res.success) {
            toast(t('saved'), 'success');
            app.closeModal('role-modal');
            loadRoleTable();
        } else {
            toast(res.message || t('failed'), 'error');
        }
    }

    async function deleteRole(role) {
        if (!confirm(`${t('deleteRoleConfirm')}\n${role.name}`)) return;
        const res = await api('DELETE', `/api/admin/roles/${role.id}`);
        toast(res.success ? t('deleted') : (res.message || t('failed')), res.success ? 'success' : 'error');
        if (res.success) loadRoleTable();
    }

    return { initUsersPage, initRolesPage, esc, can };
})();
