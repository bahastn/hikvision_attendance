// Hikvision Attendance Admin - Main JavaScript

class AttendanceApp {
    constructor() {
        this.stompClient = null;
        this.connected = false;
        this.reconnectAttempts = 0;
        this.maxReconnectAttempts = 5;
        this.currentPage = window.location.pathname;
        this.t = window.i18n || {}; // Translation strings injected from Thymeleaf
        this.init();
    }

    // Helper to get translation
    translate(key, fallback) {
        return this.t[key] || fallback || key;
    }

    /**
     * Display name for an employee object: Kurdish name when the UI is in Kurdish
     * (RTL), English otherwise. Falls back to the other when the preferred one
     * is empty, then to a plain dash.
     */
    displayName(emp) {
        if (!emp) return '-';
        const kurdish = this.t.isRTL;
        const primary = kurdish ? emp.fullNameKurdish : emp.fullName;
        const fallback = kurdish ? emp.fullName : emp.fullNameKurdish;
        return primary || fallback || '-';
    }

    init() {
        this.connectWebSocket();
        this.initNavigation();
        this.initTooltips();
        this.startSyncIndicator();
        this.initSidebar();

        // Page-specific initialization
        if (this.currentPage === '/' || this.currentPage === '/dashboard') {
            this.initDashboard();
        } else if (this.currentPage === '/attendance') {
            this.initAttendancePage();
        } else if (this.currentPage === '/employees') {
            this.initEmployeesPage();
        } else if (this.currentPage === '/face-library') {
            this.initFaceLibraryPage();
        } else if (this.currentPage === '/camera-sync') {
            this.initCameraSyncPage();
        } else if (this.currentPage === '/cameras') {
            this.initCamerasPage();
        } else if (this.currentPage === '/live-view') {
            this.initLiveViewPage();
        } else if (this.currentPage === '/leaves') {
            this.initLeavesPage();
        } else if (this.currentPage === '/groups') {
            this.initGroupsPage();
        } else if (this.currentPage === '/shifts') {
            this.initShiftsPage();
        } else if (this.currentPage === '/shift-rules') {
            this.initShiftRulesPage();
        } else if (this.currentPage === '/shift-schedule') {
            this.initShiftSchedulePage();
        }
    }

    // ==================== SIDEBAR TOGGLE ====================
    initSidebar() {
        // Restore sidebar state from localStorage
        const collapsed = localStorage.getItem('sidebarCollapsed') === 'true';
        const sidebar = document.getElementById('sidebar');
        const mainContent = document.getElementById('main-content');
        if (collapsed && sidebar) {
            sidebar.classList.add('collapsed');
            if (mainContent) {
                const isRTL = document.documentElement.getAttribute('dir') === 'rtl';
                if (isRTL) {
                    mainContent.style.marginRight = '60px';
                } else {
                    mainContent.style.marginLeft = '60px';
                }
            }
        }
    }

    toggleSidebar() {
        const sidebar = document.getElementById('sidebar');
        const mainContent = document.getElementById('main-content');
        if (sidebar) {
            sidebar.classList.toggle('collapsed');
            const isCollapsed = sidebar.classList.contains('collapsed');
            localStorage.setItem('sidebarCollapsed', isCollapsed);
            // Also toggle main-content margin directly for reliability
            if (mainContent) {
                const isRTL = document.documentElement.getAttribute('dir') === 'rtl';
                if (isCollapsed) {
                    if (isRTL) {
                        mainContent.style.marginRight = '60px';
                        mainContent.style.marginLeft = '';
                    } else {
                        mainContent.style.marginLeft = '60px';
                        mainContent.style.marginRight = '';
                    }
                } else {
                    mainContent.style.marginLeft = '';
                    mainContent.style.marginRight = '';
                }
            }
        }
    }

    // ==================== WEBSOCKET ====================
    connectWebSocket() {
        const socket = new SockJS('/ws-attendance');
        this.stompClient = Stomp.over(socket);

        this.stompClient.connect({},
            (frame) => {
                console.log('WebSocket Connected: ' + frame);
                this.connected = true;
                this.reconnectAttempts = 0;
                this.updateConnectionStatus(true);

                // Subscribe to topics
                this.stompClient.subscribe('/topic/attendance', (message) => {
                    this.handleAttendanceUpdate(JSON.parse(message.body));
                });

                this.stompClient.subscribe('/topic/stats', (message) => {
                    this.handleStatsUpdate(JSON.parse(message.body));
                });

                this.stompClient.subscribe('/topic/detection', (message) => {
                    this.handleNewDetection(JSON.parse(message.body));
                });

                this.stompClient.subscribe('/topic/camera', (message) => {
                    this.handleCameraStatus(JSON.parse(message.body));
                });
            },
            (error) => {
                console.error('WebSocket Error: ', error);
                this.connected = false;
                this.updateConnectionStatus(false);
                this.reconnectWebSocket();
            }
        );
    }

    reconnectWebSocket() {
        if (this.reconnectAttempts < this.maxReconnectAttempts) {
            this.reconnectAttempts++;
            console.log(`Reconnecting... Attempt ${this.reconnectAttempts}`);
            setTimeout(() => this.connectWebSocket(), 3000);
        }
    }

    updateConnectionStatus(connected) {
        const dotEl = document.getElementById('status-dot');
        const textEl = document.getElementById('connection-text');
        if (dotEl) {
            dotEl.className = connected ? 'status-dot' : 'status-dot offline';
        }
        if (textEl) {
            textEl.textContent = connected
                ? this.translate('connected', 'Connected')
                : this.translate('disconnected', 'Disconnected');
        }
    }

    handleAttendanceUpdate(message) {
        console.log('Attendance update:', message);
        this.showToast(`${message.employeeName} - ${message.status}`, 'info');
        this.refreshCurrentData();
    }

    handleStatsUpdate(stats) {
        console.log('Stats update:', stats);
        this.updateStatsDisplay(stats);
    }

    handleNewDetection(detection) {
        console.log('New detection:', detection);
        this.showToast(
            `${this.translate('detected', 'Detected')}: ${detection.employeeName} (${detection.direction})`,
            'success'
        );
        this.addDetectionToTable(detection);
        this.addLiveDetection(detection);
        this.addRealtimeAttendance(detection);
    }

    handleCameraStatus(status) {
        console.log('Camera status:', status);
        this.showToast(`${this.translate('cameraStatus', 'Camera')} ${status.deviceId}: ${status.status}`, 'info');
    }

    // ==================== AJAX HELPERS ====================
    async ajaxGet(url) {
        try {
            const response = await fetch(url, {
                method: 'GET',
                headers: { 'Content-Type': 'application/json' }
            });
            return await response.json();
        } catch (error) {
            console.error('GET Error:', error);
            throw error;
        }
    }

    async ajaxPost(url, data) {
        try {
            const response = await fetch(url, {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            return await response.json();
        } catch (error) {
            console.error('POST Error:', error);
            throw error;
        }
    }

    async ajaxPut(url, data) {
        try {
            const response = await fetch(url, {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(data)
            });
            return await response.json();
        } catch (error) {
            console.error('PUT Error:', error);
            throw error;
        }
    }

    async ajaxDelete(url) {
        try {
            const response = await fetch(url, { method: 'DELETE' });
            return await response.json();
        } catch (error) {
            console.error('DELETE Error:', error);
            throw error;
        }
    }

    /** Multipart POST (file uploads). Let the browser set the Content-Type boundary. */
    async ajaxUpload(url, formData) {
        try {
            const response = await fetch(url, {
                method: 'POST',
                body: formData
            });
            return await response.json();
        } catch (error) {
            console.error('UPLOAD Error:', error);
            throw error;
        }
    }

    // ==================== DASHBOARD ====================
    initDashboard() {
        this.loadDashboardStats();
        this.loadRecentDetections();
        this.loadRealtimeAttendance();
        this.loadOnLeaveEmployees();

        // Auto-refresh every 2 seconds
        setInterval(() => {
            this.loadDashboardStats();
            this.loadRecentDetections();
            this.loadRealtimeAttendance();
            this.loadOnLeaveEmployees();
        }, 2000);
    }

    // ==================== REAL-TIME ATTENDANCE PANEL ====================
    realtimeStatusClass(status) {
        const s = (status || '').toUpperCase();
        if (s === 'PRESENT') return 'ontime';
        if (s === 'LATE' || s === 'EARLY_LEAVE') return 'late';
        return 'neutral';
    }

    realtimeBadgeLabel(status) {
        const s = (status || '').toUpperCase();
        if (s === 'PRESENT') return this.translate('rtStatusOnTime', 'ON TIME');
        if (s === 'LATE') return this.translate('rtStatusLate', 'LATE');
        return this.getStatusLabel(s);
    }

    rtItemHtml(rec) {
        const cls = this.realtimeStatusClass(rec.status);
        const photo = rec.employeePhotoUrl
            ? `<img src="${rec.employeePhotoUrl}" class="rt-photo" alt="employee">`
            : `<div class="rt-photo rt-photo-empty"><i class="fas fa-user"></i></div>`;
        const capture = rec.faceImageUrl
            ? `<img src="${rec.faceImageUrl}" class="rt-photo" alt="capture">`
            : `<div class="rt-photo rt-photo-empty"><i class="fas fa-camera"></i></div>`;
        return `
            <div class="rt-photos">
                <div class="rt-photo-cap">${photo}<span>${this.translate('rtEmployee', 'Employee')}</span></div>
                <div class="rt-photo-cap">${capture}<span>${this.translate('rtCapture', 'Capture')}</span></div>
            </div>
            <div class="rt-info">
                <div class="rt-name">${rec.employeeName}</div>
                <div class="rt-meta">${rec.employeeId} &bull; ${this.formatTime(rec.checkIn)} &bull; ${rec.direction || 'IN'} &bull; ${rec.similarityScore ? rec.similarityScore.toFixed(0) + '%' : '-'}</div>
            </div>
            <div class="rt-badge rt-${cls}">${this.realtimeBadgeLabel(rec.status)}</div>`;
    }

    async loadRealtimeAttendance() {
        try {
            const response = await this.ajaxGet('/api/attendance/today');
            if (response.success) {
                this.renderRealtimeAttendance(response.data);
            }
        } catch (e) {
            console.error('Failed to load real-time attendance:', e);
        }
    }

    renderRealtimeAttendance(records) {
        const container = document.getElementById('realtime-attendance');
        if (!container) return;
        if (!records || records.length === 0) {
            container.innerHTML = `<div style="padding:20px;text-align:center;color:var(--hk-text-muted);">${this.translate('waitingDetections', 'Waiting for detections...')}</div>`;
            return;
        }
        const sorted = [...records].sort((a, b) => new Date(b.checkIn) - new Date(a.checkIn));
        container.innerHTML = sorted
            .map(r => `<div class="rt-item rt-${this.realtimeStatusClass(r.status)}">${this.rtItemHtml(r)}</div>`)
            .join('');
    }

    addRealtimeAttendance(detection) {
        const container = document.getElementById('realtime-attendance');
        if (!container) return;
        const placeholder = container.querySelector('div[style*="text-align:center"]');
        if (placeholder) container.innerHTML = '';
        const item = document.createElement('div');
        item.className = `rt-item rt-new rt-${this.realtimeStatusClass(detection.status)}`;
        item.innerHTML = this.rtItemHtml({
            employeePhotoUrl: detection.employeePhotoUrl,
            faceImageUrl: detection.faceImageUrl,
            employeeName: detection.employeeName,
            employeeId: detection.employeeId,
            checkIn: detection.timestamp,
            direction: detection.direction,
            similarityScore: detection.similarityScore,
            status: detection.status
        });
        container.prepend(item);
        while (container.children.length > 30) {
            container.removeChild(container.lastChild);
        }
    }

    async loadOnLeaveEmployees() {
        try {
            const response = await this.ajaxGet('/api/leaves/active-today');
            if (response.success) {
                this.renderOnLeaveList(response.data);
            }
        } catch (e) {
            console.error('Failed to load on-leave employees:', e);
        }
    }

    renderOnLeaveList(leaves) {
        const container = document.getElementById('on-leave-list');
        if (!container) return;

        if (leaves.length === 0) {
            container.innerHTML = `<div style="padding:20px;text-align:center;color:var(--hk-text-muted);">${this.translate('noOneOnLeave', 'No employees on leave')}</div>`;
            return;
        }

        const today = new Date();
        today.setHours(0, 0, 0, 0);

        container.innerHTML = leaves.map(leave => {
            const endDate = new Date(leave.endDate);
            endDate.setHours(0, 0, 0, 0);
            const diffTime = endDate - today;
            const daysRemaining = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
            const typeKey = 'leaveType' + this.formatLeaveTypeKey(leave.leaveType);
            const typeLabel = this.translate(typeKey, leave.leaveType);

            let remainingText;
            let remainingColor = 'var(--hk-info)';
            if (daysRemaining <= 0) {
                remainingText = this.translate('leaveLastDay', 'Last day');
                remainingColor = 'var(--hk-warning)';
            } else {
                remainingText = daysRemaining + ' ' + (daysRemaining > 1 ? this.translate('leaveDays', 'days') : this.translate('leaveDay', 'day'));
            }

            return `
            <div class="live-detection-item" style="padding:15px 20px;">
                <div class="face-thumb face-thumb-empty" style="width:42px;height:42px;">
                    <i class="fas fa-user"></i>
                </div>
                <div class="detection-info">
                    <div class="detection-name">${leave.employeeName}</div>
                    <div class="detection-meta">
                        ${leave.employeeId} &bull; ${typeLabel}
                    </div>
                    <div class="detection-meta" style="margin-top:4px;">
                        <i class="fas fa-calendar"></i> ${this.formatDate(leave.startDate)} - ${this.formatDate(leave.endDate)}
                    </div>
                </div>
                <div style="text-align:center;flex-shrink:0;">
                    <div style="font-size:16px;font-weight:700;color:${remainingColor};">${remainingText}</div>
                    <div style="font-size:10px;color:var(--hk-text-muted);">${this.translate('leaveDaysRemaining', 'remaining')}</div>
                </div>
            </div>
        `;
        }).join('');
    }

    async loadDashboardStats() {
        try {
            // Load employee count
            const statsResponse = await this.ajaxGet('/api/attendance/stats');
            if (statsResponse.success) {
                const stats = statsResponse.data;
                document.getElementById('stat-total-employees').textContent = stats.totalEmployees || 0;
            }

            // Load today's attendance for detailed stats
            const attendanceResponse = await this.ajaxGet('/api/attendance/today');
            if (attendanceResponse.success) {
                this.calculateAndRenderAttendanceStats(attendanceResponse.data);
            }
        } catch (e) {
            console.error('Failed to load stats:', e);
        }
    }

    calculateAndRenderAttendanceStats(records) {
        const totalEmployees = parseInt(document.getElementById('stat-total-employees').textContent) || 0;
        const totalRecords = records.length;

        let present = 0, late = 0, checkedOut = 0, onTime = 0;
        let totalWorkSeconds = 0;
        let workHoursCount = 0;

        records.forEach(r => {
            const status = (r.status || '').toUpperCase();
            if (status === 'PRESENT') present++;
            if (status === 'LATE') late++;
            if (r.checkOut) checkedOut++;
            if (r.checkIn) {
                const checkInHour = new Date(r.checkIn).getHours();
                if (checkInHour < 9) onTime++;
            }
            if (r.workHours) {
                totalWorkSeconds += r.workHours * 3600;
                workHoursCount++;
            }
        });

        const absent = Math.max(0, totalEmployees - totalRecords);
        const attendanceRate = totalEmployees > 0 ? Math.round((totalRecords / totalEmployees) * 100) : 0;
        const presentPct = totalEmployees > 0 ? Math.round((present / totalEmployees) * 100) : 0;
        const latePct = totalEmployees > 0 ? Math.round((late / totalEmployees) * 100) : 0;
        const absentPct = totalEmployees > 0 ? Math.round((absent / totalEmployees) * 100) : 0;
        const avgHours = workHoursCount > 0 ? (totalWorkSeconds / workHoursCount / 3600).toFixed(1) : '-';

        // Update stat cards
        this.setText('stat-present', totalRecords);
        this.setText('stat-late', late);
        this.setText('stat-absent', absent);

        // Update summary numbers
        this.setText('summary-total', totalRecords);
        this.setText('summary-present-pct', presentPct + '%');
        this.setText('summary-late-pct', latePct + '%');
        this.setText('summary-absent-pct', absentPct + '%');

        // Update legend counts
        this.setText('legend-present-count', present);
        this.setText('legend-late-count', late);
        this.setText('legend-absent-count', absent);

        // Update summary bar widths
        this.setWidth('bar-present', presentPct);
        this.setWidth('bar-late', latePct);
        this.setWidth('bar-absent', absentPct);

        // Update mini stats
        this.setText('stat-checked-in', totalRecords);
        this.setText('stat-checked-out', checkedOut);
        this.setText('stat-on-time', onTime);
        this.setText('stat-avg-hours', avgHours !== '-' ? avgHours + 'h' : '-');

        // Update progress ring
        const circumference = 2 * Math.PI * 52; // r=52
        const ring = document.getElementById('attendance-ring');
        if (ring) {
            const offset = circumference - (attendanceRate / 100) * circumference;
            ring.style.strokeDashoffset = offset;
            const ringColor = attendanceRate >= 80 ? 'var(--hk-success)' : attendanceRate >= 50 ? 'var(--hk-warning)' : 'var(--hk-accent)';
            ring.style.stroke = ringColor;
        }
        this.setText('attendance-rate-text', attendanceRate + '%');
    }

    setText(id, value) {
        const el = document.getElementById(id);
        if (el) el.textContent = value;
    }

    setWidth(id, pct) {
        const el = document.getElementById(id);
        if (el) el.style.width = pct + '%';
    }

    async loadRecentDetections() {
        try {
            const response = await this.ajaxGet('/api/attendance/today');
            if (response.success) {
                this.renderDetectionList(response.data);
            }
        } catch (e) {
            console.error('Failed to load detections:', e);
        }
    }

    renderDetectionList(records) {
        const tbody = document.getElementById('recent-detections-body');
        if (!tbody) return;

        if (records.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center;color:var(--hk-text-muted)">${this.translate('noAttendance', 'No attendance records yet')}</td></tr>`;
            return;
        }

        tbody.innerHTML = records.slice(0, 10).map(record => `
            <tr class="${record.direction === 'IN' ? 'new-record' : ''}">
                <td>${record.faceImageUrl
                    ? `<img src="${record.faceImageUrl}" class="face-thumb" alt="face">`
                    : '<div class="face-thumb face-thumb-empty"><i class="fas fa-user"></i></div>'}</td>
                <td>${record.employeeId}</td>
                <td>${record.employeeName}</td>
                <td>${record.department || '-'}</td>
                <td>${this.formatTime(record.checkIn)}</td>
                <td>${record.checkOut ? this.formatTime(record.checkOut) : '-'}</td>
                <td><span class="badge badge-${record.status.toLowerCase()}">${this.getStatusLabel(record.status)}</span></td>
                <td>${record.similarityScore ? record.similarityScore.toFixed(1) + '%' : '-'}</td>
            </tr>
        `).join('');
    }

    getStatusLabel(status) {
        const key = 'status' + status.charAt(0).toUpperCase() + status.slice(1).toLowerCase();
        return this.translate(key, status);
    }

    updateStatsDisplay(stats) {
        const presentEl = document.getElementById('stat-present');
        const absentEl = document.getElementById('stat-absent');
        const lateEl = document.getElementById('stat-late');

        if (presentEl) presentEl.textContent = stats.totalPresent || 0;
        if (absentEl) absentEl.textContent = stats.totalAbsent || 0;
        if (lateEl) lateEl.textContent = stats.totalLate || 0;
    }

    // ==================== ATTENDANCE PAGE ====================
    initAttendancePage() {
        this.loadAttendanceRecords();

        // Search handler
        const searchInput = document.getElementById('attendance-search');
        if (searchInput) {
            searchInput.addEventListener('input', (e) => {
                this.filterAttendanceTable(e.target.value);
            });
        }

        // Date filter
        const dateFilter = document.getElementById('date-filter');
        if (dateFilter) {
            dateFilter.addEventListener('change', (e) => {
                this.loadAttendanceByDate(e.target.value);
            });
        }

        // Auto-refresh
        setInterval(() => this.loadAttendanceRecords(), 2000);
    }

    async loadAttendanceRecords() {
        // Respect the date filter if the user picked a date
        const dateFilter = document.getElementById('date-filter');
        const selectedDate = dateFilter && dateFilter.value ? dateFilter.value : null;
        if (selectedDate) {
            await this.loadAttendanceByDate(selectedDate);
            return;
        }
        try {
            const response = await this.ajaxGet('/api/attendance/today');
            if (response.success) {
                this.renderAttendanceTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load attendance:', e);
        }
    }

    async loadAttendanceByDate(date) {
        try {
            const response = await this.ajaxGet(`/api/attendance/date/${date}`);
            if (response.success) {
                this.renderAttendanceTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load attendance by date:', e);
        }
    }

    renderAttendanceTable(records) {
        const tbody = document.getElementById('attendance-table-body');
        if (!tbody) return;

        if (records.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" style="text-align:center;color:var(--hk-text-muted)">${this.translate('noRecords', 'No records found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = records.map(record => {
            const name = record.employeeName || '-';
            const status = record.status || 'PRESENT';
            return `
            <tr data-employee-id="${record.employeeId}" data-name="${name.toLowerCase()}">
                <td>${record.employeeId}</td>
                <td>${name}</td>
                <td>${record.department || '-'}</td>
                <td>${record.attendanceDate}</td>
                <td>${this.formatTime(record.checkIn)}</td>
                <td>${record.checkOut ? this.formatTime(record.checkOut) : '-'}</td>
                <td>${record.workHours ? record.workHours.toFixed(2) + 'h' : '-'}</td>
                <td><span class="badge badge-${status.toLowerCase()}">${this.getStatusLabel(status)}</span></td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.manualCheckout(${record.id})"
                        ${record.checkOut ? 'disabled' : ''} title="${this.translate('checkOutSuccess', 'Check Out')}">
                        <i class="fas fa-sign-out-alt"></i>
                    </button>
                </td>
            </tr>
        `;
        }).join('');
    }

    filterAttendanceTable(query) {
        const rows = document.querySelectorAll('#attendance-table-body tr');
        const lowerQuery = query.toLowerCase();
        rows.forEach(row => {
            const empId = row.getAttribute('data-employee-id') || '';
            const name = row.getAttribute('data-name') || '';
            row.style.display = (empId.includes(lowerQuery) || name.includes(lowerQuery)) ? '' : 'none';
        });
    }

    async manualCheckout(recordId) {
        try {
            const response = await this.ajaxPost(`/api/attendance/manual-checkout/${recordId}`);
            if (response.success) {
                this.showToast(this.translate('checkOutSuccess', 'Check-out successful'), 'success');
                this.loadAttendanceRecords();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('checkOutFailed', 'Check-out failed'), 'error');
        }
    }

    addDetectionToTable(detection) {
        // Refresh the table to show new detection
        if (this.currentPage === '/attendance') {
            this.loadAttendanceRecords();
        } else if (this.currentPage === '/' || this.currentPage === '/dashboard') {
            this.loadRecentDetections();
        }
    }

    // ==================== LIVE VIEW PAGE ====================
    initLiveViewPage() {
        this.initLiveSnapshot();
        this.initLiveViewControls();
        this.loadTodayDetectionsForLiveView();
        // Poll for today's detections to populate the live list
        setInterval(() => this.loadTodayDetectionsForLiveView(), 2000);
    }

    // Refresh JPEG snapshots from the server — browsers can't play RTSP directly.
    initLiveSnapshot() {
        setInterval(() => {
            document.querySelectorAll('img.live-snapshot').forEach(img => {
                const base = img.dataset.src;
                if (!base) return;
                img.src = base + (base.includes('?') ? '&' : '?') + 't=' + Date.now();
            });
        }, 700);
    }

    // Tile resize slider + fullscreen camera viewer with camera switching.
    initLiveViewControls() {
        const tiles = Array.from(document.querySelectorAll('.camera-tile'));
        this.fsOverlay = document.getElementById('camera-fullscreen');
        if (!tiles.length || !this.fsOverlay) return;

        this.liveCameras = tiles.map(tile => ({
            name: tile.querySelector('.camera-tile-label span')?.textContent?.trim() || 'Camera',
            src: tile.querySelector('img.live-snapshot')?.dataset.src || ''
        }));
        this.fsIndex = 0;
        this.fsImg = document.getElementById('fullscreen-img');
        this.fsSelect = document.getElementById('fs-camera-select');

        // --- Resizable tiles ---
        const slider = document.getElementById('tile-size-slider');
        const applyTileSize = (value) => tiles.forEach(t => t.style.height = value + 'px');
        if (slider) {
            const saved = localStorage.getItem('liveViewTileSize');
            if (saved) {
                slider.value = saved;
                applyTileSize(saved);
            }
            slider.addEventListener('input', () => {
                applyTileSize(slider.value);
                localStorage.setItem('liveViewTileSize', slider.value);
            });
        }

        // --- Fullscreen viewer ---
        if (this.fsSelect) {
            this.fsSelect.innerHTML = this.liveCameras
                .map((cam, i) => `<option value="${i}">${cam.name}</option>`)
                .join('');
            this.fsSelect.addEventListener('change', () => this.showCameraFullscreen(parseInt(this.fsSelect.value, 10)));
        }

        tiles.forEach((tile, i) => {
            tile.querySelector('.tile-fullscreen-btn')?.addEventListener('click', (e) => {
                e.stopPropagation();
                this.openCameraFullscreen(i);
            });
        });

        document.getElementById('fs-close')?.addEventListener('click', () => this.closeCameraFullscreen());
        document.getElementById('fs-prev')?.addEventListener('click', () => this.stepCameraFullscreen(-1));
        document.getElementById('fs-next')?.addEventListener('click', () => this.stepCameraFullscreen(1));

        document.addEventListener('keydown', (e) => {
            if (this.fsOverlay.style.display === 'none') return;
            if (e.key === 'Escape') this.closeCameraFullscreen();
            else if (e.key === 'ArrowLeft') this.stepCameraFullscreen(-1);
            else if (e.key === 'ArrowRight') this.stepCameraFullscreen(1);
        });

        // Close the overlay when the user exits browser fullscreen (e.g. Esc)
        document.addEventListener('fullscreenchange', () => {
            if (!document.fullscreenElement && this.fsOverlay.style.display !== 'none') {
                this.fsOverlay.style.display = 'none';
            }
        });
    }

    openCameraFullscreen(index) {
        this.fsOverlay.style.display = 'flex';
        this.showCameraFullscreen(index);
        if (this.fsOverlay.requestFullscreen) {
            this.fsOverlay.requestFullscreen().catch(() => {});
        }
    }

    showCameraFullscreen(index) {
        if (!this.liveCameras.length) return;
        this.fsIndex = (index + this.liveCameras.length) % this.liveCameras.length;
        const cam = this.liveCameras[this.fsIndex];
        this.fsImg.dataset.src = cam.src;
        this.fsImg.src = cam.src + (cam.src.includes('?') ? '&' : '?') + 't=' + Date.now();
        const title = document.getElementById('fs-title');
        if (title) title.textContent = cam.name;
        if (this.fsSelect) this.fsSelect.value = this.fsIndex;
    }

    stepCameraFullscreen(step) {
        this.showCameraFullscreen(this.fsIndex + step);
    }

    closeCameraFullscreen() {
        if (document.fullscreenElement) {
            document.exitFullscreen().catch(() => {});
        }
        this.fsOverlay.style.display = 'none';
    }

    async loadTodayDetectionsForLiveView() {
        try {
            const response = await this.ajaxGet('/api/attendance/today');
            if (response.success) {
                this.renderLiveDetections(response.data.slice(0, 20));
            }
        } catch (e) {
            console.error('Failed to load live detections:', e);
        }
    }

    renderLiveDetections(records) {
        const container = document.getElementById('live-detections');
        if (!container) return;

        if (records.length === 0) {
            container.innerHTML = `<div style="padding:20px;text-align:center;color:var(--hk-text-muted);">${this.translate('waitingDetections', 'Waiting for detections...')}</div>`;
            return;
        }

        container.innerHTML = records.map(record => `
            <div class="live-detection-item">
                ${record.faceImageUrl
                    ? `<img src="${record.faceImageUrl}" class="face-thumb" alt="face">`
                    : '<div class="face-thumb face-thumb-empty"><i class="fas fa-user"></i></div>'}
                <div class="detection-info">
                    <div class="detection-name">${record.employeeName}</div>
                    <div class="detection-meta">
                        ${record.employeeId} &bull; ${this.formatTime(record.checkIn)} &bull; ${record.direction || 'IN'}
                    </div>
                </div>
                <div class="detection-similarity">${record.similarityScore ? record.similarityScore.toFixed(0) + '%' : '-'}</div>
            </div>
        `).join('');
    }

    addLiveDetection(detection) {
        const container = document.getElementById('live-detections');
        if (!container) return;

        const placeholder = container.querySelector('div[style*="text-align:center"]');
        if (placeholder) container.innerHTML = '';

        const item = document.createElement('div');
        item.className = 'live-detection-item';
        item.innerHTML = `
            ${detection.faceImageUrl
                ? `<img src="${detection.faceImageUrl}" class="face-thumb" alt="face">`
                : '<div class="face-thumb face-thumb-empty"><i class="fas fa-user"></i></div>'}
            <div class="detection-info">
                <div class="detection-name">${detection.employeeName}</div>
                <div class="detection-meta">
                    ${detection.employeeId} &bull; ${this.formatTime(detection.timestamp)} &bull; ${detection.direction || 'IN'}
                </div>
            </div>
            <div class="detection-similarity">${detection.similarityScore ? detection.similarityScore.toFixed(0) + '%' : '-'}</div>
        `;
        container.prepend(item);

        // Keep only the newest 20
        while (container.children.length > 20) {
            container.removeChild(container.lastChild);
        }
    }

    // ==================== EMPLOYEES PAGE ====================
    initEmployeesPage() {
        this.loadEmployees();

        document.getElementById('btn-add-employee')?.addEventListener('click', () => {
            this.openEmployeeModal();
        });

        document.getElementById('employee-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveEmployee();
        });

        document.getElementById('emp-photo')?.addEventListener('change', (e) => {
            this.previewEmployeePhoto(e.target.files[0]);
        });
    }

    async loadEmployees() {
        try {
            const response = await this.ajaxGet('/api/employees');
            if (response.success) {
                this.renderEmployeesTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load employees:', e);
        }
    }

    renderEmployeesTable(employees) {
        const tbody = document.getElementById('employees-table-body');
        if (!tbody) return;

        tbody.innerHTML = employees.map(emp => `
            <tr>
                <td>${emp.employeeId}</td>
                <td>
                    ${emp.faceImageUrl
                        ? `<img src="${emp.faceImageUrl}" class="face-thumb" alt="face">`
                        : '<div class="face-thumb face-thumb-empty"><i class="fas fa-user"></i></div>'}
                </td>
                <td>${this.displayName(emp)}</td>
                <td>${emp.department || '-'}</td>
                <td>${emp.position || '-'}</td>
                <td>${emp.email || '-'}</td>
                <td>
                    <span class="badge ${emp.isActive ? 'badge-present' : 'badge-absent'}">
                        ${emp.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>
                    ${emp.faceCameras && emp.faceCameras.length
                        ? emp.faceCameras.map(name => `<span class="badge badge-leave">${name}</span>`).join(' ')
                        : '-'}
                </td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.openCameraTargetsModal(${emp.id}, this.dataset.name)" data-name="${this.displayName(emp)}" title="${this.translate('employees.manageCameras', 'Cameras')}">
                        <i class="fas fa-camera"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editEmployee(${emp.id})" title="${this.translate('editEmployee', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    ${emp.isActive
                        ? `<button class="btn btn-sm btn-warning btn-icon" onclick="app.deactivateEmployee(${emp.id})" title="${this.translate('employees.deactivate', 'Deactivate')}">
                            <i class="fas fa-user-slash"></i>
                        </button>`
                        : `<button class="btn btn-sm btn-success btn-icon" onclick="app.activateEmployee(${emp.id})" title="${this.translate('employees.activate', 'Activate')}">
                            <i class="fas fa-user-check"></i>
                        </button>`}
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteEmployee(${emp.id})" title="${this.translate('deleteFailed', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    async openEmployeeModal(employee = null) {
        const modal = document.getElementById('employee-modal');
        const title = document.getElementById('modal-title');
        const form = document.getElementById('employee-form');

        title.textContent = employee
            ? this.translate('editEmployee', 'Edit Employee')
            : this.translate('addEmployee', 'Add Employee');
        document.getElementById('employee-id-hidden').value = employee?.id || '';
        document.getElementById('emp-id').value = employee?.employeeId || '';
        document.getElementById('emp-name').value = employee?.fullName || '';
        document.getElementById('emp-name-kurdish').value = employee?.fullNameKurdish || '';
        document.getElementById('emp-dept').value = employee?.department || '';
        document.getElementById('emp-position').value = employee?.position || '';
        document.getElementById('emp-email').value = employee?.email || '';
        document.getElementById('emp-phone').value = employee?.phone || '';

        const photoInput = document.getElementById('emp-photo');
        if (photoInput) photoInput.value = '';
        this.setEmployeePhotoPreview(employee?.faceImageUrl || null);
        if (employee) {
            // When editing, pre-check only the cameras that already have the face,
            // so saving with a new photo does not push it to every camera.
            this.loadCameraCheckboxes('emp-cameras', await this.getEnrolledCameraIds(employee.id));
        } else {
            this.loadCameraCheckboxes('emp-cameras');
        }

        modal.classList.add('active');
    }

    async saveEmployee() {
        const id = document.getElementById('employee-id-hidden').value;
        const data = {
            employeeId: document.getElementById('emp-id').value,
            fullName: document.getElementById('emp-name').value,
            fullNameKurdish: document.getElementById('emp-name-kurdish').value,
            department: document.getElementById('emp-dept').value,
            position: document.getElementById('emp-position').value,
            email: document.getElementById('emp-email').value,
            phone: document.getElementById('emp-phone').value
        };

        const photoInput = document.getElementById('emp-photo');
        const photoFile = photoInput && photoInput.files.length > 0 ? photoInput.files[0] : null;
        const cameraIds = this.getSelectedCameraIds('emp-cameras');

        try {
            let response;
            if (photoFile) {
                response = await this.saveEmployeeWithPhoto(id, data, photoFile, cameraIds);
            } else {
                response = id
                    ? await this.ajaxPut(`/api/employees/${id}`, data)
                    : await this.ajaxPost('/api/employees', data);
            }

            if (response.success) {
                this.showToast(
                    response.message
                        || (id ? this.translate('employeeUpdated', 'Employee updated') : this.translate('employeeCreated', 'Employee created')),
                    'success'
                );
                this.closeModal('employee-modal');
                this.loadEmployees();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    /**
     * Save with a photo: create goes to /api/employees/with-face in one request;
     * update saves the fields first, then pushes the photo to the selected cameras.
     */
    async saveEmployeeWithPhoto(id, data, photoFile, cameraIds) {
        if (id) {
            const updateResponse = await this.ajaxPut(`/api/employees/${id}`, data);
            if (!updateResponse.success) {
                return updateResponse;
            }
        }
        const formData = new FormData();
        Object.entries(data).forEach(([key, value]) => formData.append(key, value ?? ''));
        formData.append('photo', photoFile);
        if (cameraIds.length > 0) {
            formData.append('cameraIds', cameraIds.join(','));
        }
        return await this.ajaxUpload(id ? `/api/employees/${id}/face` : '/api/employees/with-face', formData);
    }

    /** Show a local preview of the chosen employee photo file. */
    previewEmployeePhoto(file) {
        if (!file) {
            const input = document.getElementById('emp-photo');
            this.setEmployeePhotoPreview(input?.dataset?.currentUrl || null);
            return;
        }
        const reader = new FileReader();
        reader.onload = (e) => this.setEmployeePhotoPreview(e.target.result);
        reader.readAsDataURL(file);
    }

    /** Toggle between the employee photo image and the empty placeholder. */
    setEmployeePhotoPreview(url) {
        const preview = document.getElementById('emp-photo-preview');
        const placeholder = document.getElementById('emp-photo-placeholder');
        if (!preview || !placeholder) return;
        if (url) {
            preview.src = url;
            preview.style.display = '';
            placeholder.style.display = 'none';
        } else {
            preview.src = '';
            preview.style.display = 'none';
            placeholder.style.display = '';
        }
    }

    /** Camera ids where the employee's face is already enrolled (null when unknown). */
    async getEnrolledCameraIds(employeeId) {
        try {
            const response = await this.ajaxGet(`/api/employees/${employeeId}/camera-targets`);
            if (response.success) {
                return (response.data || []).filter(t => t.enrolled).map(t => String(t.cameraId));
            }
        } catch (e) {
            console.warn('Could not load enrolled cameras:', e);
        }
        return null;
    }

    /** Render active cameras as checkboxes into a container (by id). All checked by default;
     *  pass checkedIds (array of string ids) to control which ones start checked. */
    async loadCameraCheckboxes(containerId, checkedIds = null) {
        const container = document.getElementById(containerId);
        if (!container) return;
        try {
            const response = await this.ajaxGet('/api/cameras/active');
            const cameras = response.success ? (response.data || []) : [];
            const isChecked = (cam) => checkedIds === null || checkedIds.includes(String(cam.id));
            container.innerHTML = cameras.length > 0
                ? cameras.map(cam => `
                    <label class="camera-check-item">
                        <input type="checkbox" value="${cam.id}" ${isChecked(cam) ? 'checked' : ''}>
                        <span>${cam.cameraName}</span>
                    </label>
                `).join('')
                : `<div class="camera-check-empty">${this.translate('faceLibraryNoCameras', 'No active cameras')}</div>`;
        } catch (e) {
            container.innerHTML = `<div class="camera-check-empty">${this.translate('faceLibraryNoCameras', 'No active cameras')}</div>`;
        }
    }

    /** Ids of the checked cameras in a checkbox container (by id). */
    getSelectedCameraIds(containerId) {
        const container = document.getElementById(containerId);
        if (!container) return [];
        return Array.from(container.querySelectorAll('input[type="checkbox"]:checked'))
            .map(cb => cb.value);
    }

    // ==================== EMPLOYEE CAMERA TARGETS ====================
    openCameraTargetsModal(employeeId, employeeName) {
        this.cameraTargetsEmployeeId = employeeId;
        this._cameraTargets = [];
        const nameEl = document.getElementById('camera-targets-employee');
        if (nameEl) nameEl.textContent = employeeName || '';
        document.getElementById('camera-targets-modal')?.classList.add('active');
        this.loadCameraTargets();
    }

    async loadCameraTargets() {
        const container = document.getElementById('camera-targets-list');
        const id = this.cameraTargetsEmployeeId;
        if (!container || !id) return;
        container.innerHTML = `<div class="camera-check-empty">${this.translate('employees.loading', 'Loading...')}</div>`;
        try {
            const response = await this.ajaxGet(`/api/employees/${id}/camera-targets`);
            if (!response.success) {
                throw new Error(response.message || 'Failed to load cameras');
            }
            const targets = response.data || [];
            this._cameraTargets = targets;
            container.innerHTML = targets.length > 0
                ? targets.map((t, i) => `
                    <div class="camera-target-row">
                        <div class="camera-target-info">
                            <div class="camera-target-name">${t.cameraName}</div>
                            <div class="camera-target-meta">
                                ${t.location ? t.location + ' &bull; ' : ''}${t.enrolledAt || ''}
                            </div>
                        </div>
                        <span class="badge ${t.enrolled ? 'badge-present' : 'badge-absent'}">
                            ${t.enrolled
                                ? this.translate('employees.enrolled', 'Enrolled')
                                : this.translate('employees.notEnrolled', 'Not enrolled')}
                        </span>
                        <div class="camera-target-actions">
                            ${t.enrolled
                                ? `<button class="btn btn-sm btn-secondary" onclick="app.removeEmployeeFromCamera(${i})">${this.translate('employees.removeFromCamera', 'Remove')}</button>`
                                : `<button class="btn btn-sm btn-primary" onclick="app.sendEmployeeToCamera(${i})">${this.translate('employees.sendToCamera', 'Send')}</button>`}
                        </div>
                    </div>
                `).join('')
                : `<div class="camera-check-empty">${this.translate('faceLibraryNoCameras', 'No active cameras')}</div>`;
        } catch (e) {
            container.innerHTML = `<div class="camera-check-empty">${e.message || this.translate('employees.loadFailed', 'Failed to load cameras')}</div>`;
        }
    }

    async sendEmployeeToCamera(index) {
        const target = this._cameraTargets[index];
        const id = this.cameraTargetsEmployeeId;
        if (!target || !id) return;
        try {
            const response = await this.ajaxPost(`/api/employees/${id}/camera-targets/${target.cameraId}`, {});
            this.showToast(response.message || this.translate('employees.sentToCamera', 'Face sent to camera'),
                response.success ? 'success' : 'error');
            if (response.success) {
                this.loadCameraTargets();
                this.loadEmployees();
            }
        } catch (e) {
            this.showToast(this.translate('employees.sendFailed', 'Send failed'), 'error');
        }
    }

    async removeEmployeeFromCamera(index) {
        const target = this._cameraTargets[index];
        const id = this.cameraTargetsEmployeeId;
        if (!target || !id) return;
        const confirmMsg = `${this.translate('employees.removeFromCameraConfirm', "Remove this employee's face from")} ${target.cameraName}?`;
        if (!confirm(confirmMsg)) return;
        try {
            const response = await this.ajaxDelete(`/api/employees/${id}/camera-targets/${target.cameraId}`);
            this.showToast(response.message || this.translate('employees.removedFromCamera', 'Face removed from camera'),
                response.success ? 'success' : 'error');
            if (response.success) {
                this.loadCameraTargets();
                this.loadEmployees();
            }
        } catch (e) {
            this.showToast(this.translate('employees.removeFailed', 'Remove failed'), 'error');
        }
    }

    async editEmployee(id) {
        try {
            const response = await this.ajaxGet(`/api/employees/${id}`);
            if (response.success) {
                this.openEmployeeModal(response.data);
            }
        } catch (e) {
            this.showToast(this.translate('loadEmployeeFailed', 'Failed to load employee'), 'error');
        }
    }

    async deleteEmployee(id) {
        if (!confirm(this.translate('deleteEmployeeConfirm', 'Are you sure?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/employees/${id}`);
            if (response.success) {
                this.showToast(this.translate('employeeDeleted', 'Employee deleted'), 'success');
                this.loadEmployees();
            }
        } catch (e) {
            this.showToast(this.translate('deleteFailed', 'Delete failed'), 'error');
        }
    }

    async activateEmployee(id) {
        try {
            const response = await this.ajaxPost(`/api/employees/${id}/activate`, {});
            if (response.success) {
                this.showToast(response.message || this.translate('employees.activated', 'Employee activated'), 'success');
                this.loadEmployees();
            } else {
                this.showToast(response.message || this.translate('employees.activateFailed', 'Activation failed'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('employees.activateFailed', 'Activation failed'), 'error');
        }
    }

    async deactivateEmployee(id) {
        if (!confirm(this.translate('employees.deactivateConfirm', 'Deactivate this employee? Their face will be removed from all cameras.'))) return;
        try {
            const response = await this.ajaxPost(`/api/employees/${id}/deactivate`, {});
            if (response.success) {
                this.showToast(response.message || this.translate('employees.deactivated', 'Employee deactivated'), 'success');
                this.loadEmployees();
            } else {
                this.showToast(response.message || this.translate('employees.deactivateFailed', 'Deactivation failed'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('employees.deactivateFailed', 'Deactivation failed'), 'error');
        }
    }

    // ==================== FACE CAPTURE LIBRARY PAGE ====================
    initFaceLibraryPage() {
        this.faceCaptures = [];
        this.loadFaceLibraryCameras();
        this.loadFaceCaptures();

        document.getElementById('btn-refresh-captures')?.addEventListener('click', () => this.loadFaceCaptures());
        document.getElementById('face-camera-filter')?.addEventListener('change', () => this.loadFaceCaptures());
        document.getElementById('capture-enroll-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveCaptureEnroll();
        });

        // Refresh the grid periodically while the page is open
        this.captureRefreshTimer = setInterval(() => this.loadFaceCaptures(), 10000);
    }

    /** Cameras for the filter dropdown. The "all cameras" label is read from the page. */
    async loadFaceLibraryCameras() {
        const filter = document.getElementById('face-camera-filter');
        if (!filter) return;
        const allLabel = filter.querySelector('option[value=""]')?.textContent
            || this.translate('faceLibraryFilterAllCameras', 'All Cameras');
        try {
            const response = await this.ajaxGet('/api/cameras');
            if (!response.success) return;
            const cameras = response.data || [];
            filter.innerHTML = `<option value="">${allLabel}</option>` + cameras
                .map(cam => `<option value="${cam.id}">${cam.cameraName}</option>`)
                .join('');
        } catch (e) {
            console.error('Failed to load cameras:', e);
        }
    }

    async loadFaceCaptures() {
        const filter = document.getElementById('face-camera-filter');
        const cameraId = filter && filter.value ? filter.value : '';
        try {
            const response = await this.ajaxGet('/api/face-captures' + (cameraId ? `?cameraId=${cameraId}` : ''));
            if (response.success) {
                this.faceCaptures = response.data || [];
                this.renderFaceCaptures(this.faceCaptures);
            }
        } catch (e) {
            console.error('Failed to load face captures:', e);
        }
    }

    renderFaceCaptures(captures) {
        const grid = document.getElementById('face-capture-grid');
        if (!grid) return;
        if (!captures || captures.length === 0) {
            grid.innerHTML = `<div style="grid-column:1/-1;text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('faceLibraryNoCaptures', 'No face captures yet')}</div>`;
            return;
        }
        grid.innerHTML = captures.map(c => `
            <div class="capture-card">
                <img class="capture-photo" src="${c.imageUrl}" alt="capture" loading="lazy">
                <div class="capture-body">
                    <span class="badge ${c.matched ? 'badge-present' : 'badge-absent'}" style="align-self:flex-start;">
                        ${c.matched ? this.translate('faceLibraryMatched', 'Matched') : this.translate('faceLibraryUnknown', 'Unknown')}
                    </span>
                    <div class="capture-name">${c.matchedEmployeeName || c.personName || this.translate('faceLibraryUnknown', 'Unknown')}</div>
                    <div class="capture-meta">
                        <i class="fas fa-video"></i> ${c.cameraName || '-'}
                        ${c.similarity != null ? ` &bull; ${Number(c.similarity).toFixed(0)}%` : ''}
                    </div>
                    <div class="capture-meta"><i class="far fa-clock"></i> ${this.formatTime(c.capturedAt)}</div>
                </div>
                <div class="capture-actions">
                    <button class="btn btn-primary" onclick="app.openCaptureEnrollModal(${c.id})">
                        <i class="fas fa-user-plus"></i> ${this.translate('faceLibraryAddToEmployee', 'Add to Employee')}
                    </button>
                    <button class="btn btn-secondary" onclick="app.deleteCapture(${c.id})" title="Delete">
                        <i class="fas fa-trash"></i>
                    </button>
                </div>
            </div>
        `).join('');
    }

    /** Open the "Add to Employee" modal for one capture. */
    openCaptureEnrollModal(captureId) {
        const capture = (this.faceCaptures || []).find(c => c.id === captureId);
        if (!capture) return;

        document.getElementById('capture-id-hidden').value = captureId;
        const preview = document.getElementById('capture-preview');
        if (preview) preview.src = capture.imageUrl;

        document.getElementById('cap-emp-id').value = '';
        document.getElementById('cap-emp-name').value = capture.matchedEmployeeName || capture.personName || '';
        document.getElementById('cap-emp-name-kurdish').value = '';
        document.getElementById('cap-emp-dept').value = '';
        document.getElementById('cap-emp-position').value = '';
        document.getElementById('cap-emp-email').value = '';
        document.getElementById('cap-emp-phone').value = '';

        this.loadCameraCheckboxes('capture-cameras');
        document.getElementById('capture-enroll-modal')?.classList.add('active');
    }

    /** Create the employee from a capture and push the photo to the selected cameras. */
    async saveCaptureEnroll() {
        const captureId = document.getElementById('capture-id-hidden').value;
        const formData = new FormData();
        formData.append('employeeId', document.getElementById('cap-emp-id').value);
        formData.append('fullName', document.getElementById('cap-emp-name').value);
        formData.append('fullNameKurdish', document.getElementById('cap-emp-name-kurdish').value);
        formData.append('department', document.getElementById('cap-emp-dept').value);
        formData.append('position', document.getElementById('cap-emp-position').value);
        formData.append('email', document.getElementById('cap-emp-email').value);
        formData.append('phone', document.getElementById('cap-emp-phone').value);
        const cameraIds = this.getSelectedCameraIds('capture-cameras');
        if (cameraIds.length > 0) {
            formData.append('cameraIds', cameraIds.join(','));
        }

        try {
            const response = await this.ajaxUpload(`/api/face-captures/${captureId}/enroll`, formData);
            if (response.success) {
                this.showToast(response.message || this.translate('faceLibraryEnrolled', 'Employee added and face sent to camera'), 'success');
                this.closeModal('capture-enroll-modal');
                this.loadFaceCaptures();
            } else {
                this.showToast(response.message || this.translate('faceLibraryEnrollFailed', 'Could not add employee'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('faceLibraryEnrollFailed', 'Could not add employee'), 'error');
        }
    }

    async deleteCapture(captureId) {
        if (!confirm(this.translate('faceLibraryDeleteConfirm', 'Delete this captured photo?'))) return;
        try {
            const response = await this.ajaxDelete(`/api/face-captures/${captureId}`);
            if (response.success) {
                this.showToast(this.translate('faceLibraryDeleted', 'Capture deleted'), 'success');
                this.loadFaceCaptures();
            }
        } catch (e) {
            console.error('Failed to delete capture:', e);
        }
    }

    // ==================== CAMERA FACE SYNC PAGE ====================
    initCameraSyncPage() {
        this.faceLibPeople = [];
        this.selectedCameraId = null;

        const cameraSelect = document.getElementById('sync-camera-select');
        const btnLoad = document.getElementById('btn-load-face-lib');
        const btnSync = document.getElementById('btn-sync-face-lib');
        const btnSyncAll = document.getElementById('btn-sync-all');

        cameraSelect?.addEventListener('change', () => {
            this.selectedCameraId = cameraSelect.value || null;
            const has = !!this.selectedCameraId;
            if (btnLoad) btnLoad.disabled = !has;
            if (btnSync) btnSync.disabled = !has;
            if (!has) {
                document.getElementById('face-lib-table-container').innerHTML =
                    `<div style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('cameraSyncNoCamera', 'Select a camera and click Load to see its face library.')}</div>`;
                document.getElementById('sync-stats').style.display = 'none';
            }
        });

        btnLoad?.addEventListener('click', () => this.loadCameraFaceLibrary());
        btnSync?.addEventListener('click', () => this.syncCameraFaceLibrary());
        btnSyncAll?.addEventListener('click', () => this.syncAllCameras());
    }

    async loadCameraFaceLibrary() {
        if (!this.selectedCameraId) return;
        const container = document.getElementById('face-lib-table-container');
        container.innerHTML = `<div style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('loading', 'Loading...')}</div>`;
        try {
            const response = await this.ajaxGet(`/api/cameras/${this.selectedCameraId}/face-library`);
            if (response.success && response.data) {
                this.faceLibPeople = response.data;
                this.renderFaceLibTable(this.faceLibPeople);
                this.updateSyncStats(this.faceLibPeople);
            } else {
                container.innerHTML = `<div style="text-align:center;color:var(--hk-danger,#dc3545);padding:30px;">${response.message || this.translate('cameraSyncLoadFailed', 'Could not load face library')}</div>`;
            }
        } catch (e) {
            container.innerHTML = `<div style="text-align:center;color:var(--hk-danger,#dc3545);padding:30px;">${this.translate('cameraSyncLoadFailed', 'Could not load face library')}</div>`;
        }
    }

    renderFaceLibTable(people) {
        const container = document.getElementById('face-lib-table-container');
        if (!container) return;
        if (!people || people.length === 0) {
            container.innerHTML = `<div style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('cameraSyncEmpty', 'Face library is empty')}</div>`;
            return;
        }
        const rows = people.map(p => {
            const statusBadge = p.inDatabase
                ? (p.employeeActive
                    ? `<span class="badge badge-present">${this.translate('cameraSyncInDb', 'In Database')}</span>`
                    : `<span class="badge badge-absent">${this.translate('cameraSyncDeactivated', 'Deactivated')}</span>`)
                : `<span class="badge badge-late">${this.translate('cameraSyncNotInDb', 'Not in DB')}</span>`;
            return `<tr>
                <td>${p.pid || '-'}</td>
                <td>${p.name || '-'}</td>
                <td>${p.certificateNumber || '-'}</td>
                <td>${statusBadge}</td>
                <td>${p.inDatabase ? (p.employeeName || '-') : '-'}</td>
                <td>
                    ${!p.inDatabase ? `<button class="btn btn-sm btn-primary" onclick="app.importPerson('${p.pid}','${p.certificateNumber || ''}')" title="${this.translate('cameraSyncImport', 'Import as Employee')}">
                        <i class="fas fa-user-plus"></i>
                    </button>` : ''}
                    <button class="btn btn-sm btn-secondary" onclick="app.deleteCameraPerson('${p.pid}')" title="${this.translate('cameraSyncDeleteFromCam', 'Remove from camera')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>`;
        }).join('');
        container.innerHTML = `<div class="table-responsive"><table class="table">
            <thead><tr>
                <th>PID</th>
                <th>${this.translate('cameraSyncColName', 'Name')}</th>
                <th>${this.translate('cameraSyncColCert', 'Certificate / Emp ID')}</th>
                <th>${this.translate('cameraSyncColStatus', 'Status')}</th>
                <th>${this.translate('cameraSyncColDbName', 'DB Employee')}</th>
                <th>${this.translate('cameraSyncColActions', 'Actions')}</th>
            </tr></thead>
            <tbody>${rows}</tbody>
        </table></div>`;
    }

    updateSyncStats(people) {
        const stats = document.getElementById('sync-stats');
        if (!stats) return;
        const total = people.length;
        const linked = people.filter(p => p.inDatabase && p.employeeActive).length;
        const notInDb = people.filter(p => !p.inDatabase).length;
        const deactivated = people.filter(p => p.inDatabase && !p.employeeActive).length;
        document.getElementById('stat-total').textContent = total;
        document.getElementById('stat-linked').textContent = linked;
        document.getElementById('stat-imported').textContent = notInDb;
        document.getElementById('stat-removed').textContent = deactivated;
        stats.style.display = 'block';
    }

    async syncCameraFaceLibrary() {
        if (!this.selectedCameraId) return;
        const btn = document.getElementById('btn-sync-face-lib');
        if (btn) { btn.disabled = true; btn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${this.translate('cameraSyncSyncing', 'Syncing...')}`; }
        try {
            const response = await this.ajaxPost(`/api/cameras/${this.selectedCameraId}/face-library/sync`, {});
            if (response.success && response.data) {
                this.showSyncResult(response.data);
                this.showToast(this.translate('cameraSyncDone', 'Sync completed'), 'success');
                this.loadCameraFaceLibrary();
            } else {
                this.showToast(response.message || this.translate('cameraSyncFailed', 'Sync failed'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('cameraSyncFailed', 'Sync failed'), 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.innerHTML = `<i class="fas fa-sync"></i> ${this.translate('cameraSyncSync', 'Sync')}`; }
        }
    }

    async syncAllCameras() {
        const btn = document.getElementById('btn-sync-all');
        if (btn) { btn.disabled = true; btn.innerHTML = `<i class="fas fa-spinner fa-spin"></i> ${this.translate('cameraSyncSyncing', 'Syncing...')}`; }
        try {
            const response = await this.ajaxPost('/api/cameras/face-library/sync-all', {});
            if (response.success && response.data) {
                let allMessages = [];
                for (const [camName, result] of Object.entries(response.data)) {
                    allMessages.push(`<strong>${camName}</strong>: ${result.imported} imported, ${result.linked} linked, ${result.removed} removed`);
                }
                this.showSyncResult({ messages: allMessages });
                this.showToast(this.translate('cameraSyncDone', 'Sync completed'), 'success');
                if (this.selectedCameraId) this.loadCameraFaceLibrary();
            } else {
                this.showToast(response.message || this.translate('cameraSyncFailed', 'Sync failed'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('cameraSyncFailed', 'Sync failed'), 'error');
        } finally {
            if (btn) { btn.disabled = false; btn.innerHTML = `<i class="fas fa-cloud-download-alt"></i> ${this.translate('cameraSyncSyncAll', 'Sync All')}`; }
        }
    }

    showSyncResult(result) {
        const panel = document.getElementById('sync-result-panel');
        if (!panel) return;
        const msgs = (result.messages || []).map(m => `<li>${m}</li>`).join('');
        const summary = result.imported !== undefined
            ? `<strong>${result.imported}</strong> imported, <strong>${result.linked}</strong> linked, <strong>${result.removed}</strong> removed, <strong>${result.skipped}</strong> skipped`
            : '';
        panel.innerHTML = `<div class="alert alert-success" style="margin-bottom:0;">
            <i class="fas fa-check-circle"></i> ${summary}
            ${msgs ? `<ul style="margin-top:8px;margin-bottom:0;padding-left:20px;">${msgs}</ul>` : ''}
        </div>`;
        panel.style.display = 'block';
    }

    /** Import a single person from the camera as an employee (calls the full sync which handles it). */
    async importPerson(pid, certNumber) {
        if (!this.selectedCameraId) return;
        try {
            const response = await this.ajaxPost(`/api/cameras/${this.selectedCameraId}/face-library/sync`, {});
            if (response.success) {
                this.showToast(this.translate('cameraSyncDone', 'Sync completed'), 'success');
                this.loadCameraFaceLibrary();
            } else {
                this.showToast(response.message || this.translate('cameraSyncFailed', 'Sync failed'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('cameraSyncFailed', 'Sync failed'), 'error');
        }
    }

    async deleteCameraPerson(pid) {
        if (!confirm(this.translate('cameraSyncDeleteConfirm', 'Remove this person from the camera face library?'))) return;
        if (!this.selectedCameraId) return;
        try {
            const response = await this.ajaxDelete(`/api/cameras/${this.selectedCameraId}/face-library/${pid}`);
            if (response.success) {
                this.showToast(this.translate('cameraSyncDeleted', 'Person removed from camera'), 'success');
                this.loadCameraFaceLibrary();
            } else {
                this.showToast(response.message || this.translate('cameraSyncDeleteFailed', 'Could not remove person'), 'error');
            }
        } catch (e) {
            this.showToast(this.translate('cameraSyncDeleteFailed', 'Could not remove person'), 'error');
        }
    }

    // ==================== CAMERAS PAGE ====================
    initCamerasPage() {
        this.loadCameras();
        this.initCameraTabs();

        document.getElementById('btn-add-camera')?.addEventListener('click', () => {
            this.openCameraModal();
        });

        document.getElementById('camera-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveCamera();
        });
    }

    initCameraTabs() {
        const tabs = document.querySelectorAll('.tab-item');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');

                const target = tab.getAttribute('data-tab');
                document.querySelectorAll('.tab-content').forEach(content => {
                    content.classList.remove('active');
                });
                document.getElementById(target)?.classList.add('active');
            });
        });
    }

    async loadCameras() {
        try {
            const response = await this.ajaxGet('/api/cameras');
            if (response.success) {
                this.renderCamerasTable(response.data);
                this.renderCameraCards(response.data);
            }
        } catch (e) {
            console.error('Failed to load cameras:', e);
        }
    }

    renderCamerasTable(cameras) {
        const tbody = document.getElementById('cameras-table-body');
        if (!tbody) return;

        tbody.innerHTML = cameras.map(cam => `
            <tr>
                <td>${cam.cameraName}</td>
                <td>${cam.ipAddress}:${cam.port}</td>
                <td>${cam.location || '-'}</td>
                <td>
                    <span class="badge ${cam.isConnected ? 'badge-present' : 'badge-absent'}">
                        ${cam.isConnected ? this.translate('online', 'Online') : this.translate('offline', 'Offline')}
                    </span>
                </td>
                <td>
                    <span class="badge ${cam.isActive ? 'badge-present' : 'badge-absent'}">
                        ${cam.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>${cam.lastSync || this.translate('never', 'Never')}</td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.testCamera(${cam.id})" title="${this.translate('cameraTestFailed', 'Test')}">
                        <i class="fas fa-plug"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editCamera(${cam.id})" title="${this.translate('editCamera', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteCamera(${cam.id})" title="${this.translate('cameraDeleteFailed', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    renderCameraCards(cameras) {
        const container = document.getElementById('camera-cards');
        if (!container) return;

        container.innerHTML = cameras.map(cam => `
            <div class="camera-config-card">
                <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:15px;">
                    <h4 style="font-size:15px;">${cam.cameraName}</h4>
                    <span class="badge ${cam.isConnected ? 'badge-present' : 'badge-absent'}">
                        ${cam.isConnected ? this.translate('online', 'Online') : this.translate('offline', 'Offline')}
                    </span>
                </div>
                <div class="config-row">
                    <span class="config-label">${this.translate('cameras.ipAddress', 'IP Address')}</span>
                    <span class="config-value">${cam.ipAddress}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">HTTP Port</span>
                    <span class="config-value">${cam.port}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">SDK Port</span>
                    <span class="config-value">${cam.sdkPort}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">WebSocket Port</span>
                    <span class="config-value">${cam.websocketPort}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">RTSP Port</span>
                    <span class="config-value">${cam.rtspPort}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">${this.translate('cameras.protocol', 'Protocol')}</span>
                    <span class="config-value">${cam.protocol.toUpperCase()}</span>
                </div>
                <div class="config-row">
                    <span class="config-label">${this.translate('table.location', 'Location')}</span>
                    <span class="config-value">${cam.location || '-'}</span>
                </div>
            </div>
        `).join('');
    }

    async testCamera(id) {
        try {
            const response = await this.ajaxPost(`/api/cameras/${id}/test`);
            this.showToast(response.message, response.success ? 'success' : 'error');
            this.loadCameras();
        } catch (e) {
            this.showToast(this.translate('cameraTestFailed', 'Test failed'), 'error');
        }
    }

    openCameraModal(camera = null) {
        const modal = document.getElementById('camera-modal');
        const title = document.getElementById('camera-modal-title');

        title.textContent = camera
            ? this.translate('editCamera', 'Edit Camera')
            : this.translate('addCamera', 'Add Camera');
        document.getElementById('camera-id-hidden').value = camera?.id || '';
        document.getElementById('cam-name').value = camera?.cameraName || '';
        document.getElementById('cam-ip').value = camera?.ipAddress || '';
        document.getElementById('cam-port').value = camera?.port || 80;
        document.getElementById('cam-username').value = camera?.username || '';
        document.getElementById('cam-password').value = '';
        document.getElementById('cam-protocol').value = camera?.protocol || 'http';
        document.getElementById('cam-sdk-port').value = camera?.sdkPort || 8000;
        document.getElementById('cam-ws-port').value = camera?.websocketPort || 7681;
        document.getElementById('cam-rtsp-port').value = camera?.rtspPort || 554;
        document.getElementById('cam-location').value = camera?.location || '';

        modal.classList.add('active');
    }

    async saveCamera() {
        const id = document.getElementById('camera-id-hidden').value;
        const password = document.getElementById('cam-password').value;
        const data = {
            cameraName: document.getElementById('cam-name').value,
            ipAddress: document.getElementById('cam-ip').value,
            port: parseInt(document.getElementById('cam-port').value),
            username: document.getElementById('cam-username').value,
            password: password || undefined,
            protocol: document.getElementById('cam-protocol').value,
            sdkPort: parseInt(document.getElementById('cam-sdk-port').value),
            websocketPort: parseInt(document.getElementById('cam-ws-port').value),
            rtspPort: parseInt(document.getElementById('cam-rtsp-port').value),
            location: document.getElementById('cam-location').value,
            isActive: true
        };

        try {
            const response = id
                ? await this.ajaxPut(`/api/cameras/${id}`, data)
                : await this.ajaxPost('/api/cameras', data);

            if (response.success) {
                this.showToast(
                    id ? this.translate('cameraUpdated', 'Camera updated') : this.translate('cameraAdded', 'Camera added'),
                    'success'
                );
                this.closeModal('camera-modal');
                this.loadCameras();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async editCamera(id) {
        try {
            const response = await this.ajaxGet('/api/cameras');
            if (response.success) {
                const camera = response.data.find(c => c.id === id);
                if (camera) this.openCameraModal(camera);
            }
        } catch (e) {
            this.showToast(this.translate('loadCameraFailed', 'Failed to load camera'), 'error');
        }
    }

    async deleteCamera(id) {
        if (!confirm(this.translate('deleteCameraConfirm', 'Are you sure?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/cameras/${id}`);
            if (response.success) {
                this.showToast(this.translate('cameraDeleted', 'Camera deleted'), 'success');
                this.loadCameras();
            }
        } catch (e) {
            this.showToast(this.translate('cameraDeleteFailed', 'Delete failed'), 'error');
        }
    }

    // ==================== LEAVES PAGE ====================
    initLeavesPage() {
        this.loadLeaves();
        this.loadActiveLeaves();
        this.loadPendingLeaves();
        this.loadApprovedLeaves();
        this.loadLeaveSummary();
        this.populateEmployeeDropdown();
        this.initLeaveTabs();

        document.getElementById('btn-add-leave')?.addEventListener('click', () => {
            this.openLeaveModal();
        });

        document.getElementById('leave-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveLeave();
        });

        // Search
        const searchInput = document.getElementById('leave-search');
        if (searchInput) {
            searchInput.addEventListener('input', (e) => {
                this.filterLeaveTable(e.target.value);
            });
        }
    }

    initLeaveTabs() {
        const tabs = document.querySelectorAll('.tab-item');
        tabs.forEach(tab => {
            tab.addEventListener('click', () => {
                tabs.forEach(t => t.classList.remove('active'));
                tab.classList.add('active');
                const target = tab.getAttribute('data-tab');
                document.querySelectorAll('.tab-content').forEach(c => c.classList.remove('active'));
                document.getElementById(target)?.classList.add('active');
            });
        });
    }

    async loadLeaveSummary() {
        try {
            const [activeRes, pendingRes, allRes] = await Promise.all([
                this.ajaxGet('/api/leaves/active-today'),
                this.ajaxGet('/api/leaves/pending'),
                this.ajaxGet('/api/leaves')
            ]);

            if (activeRes.success) this.setText('leave-active-count', activeRes.data.length);
            if (pendingRes.success) this.setText('leave-pending-count', pendingRes.data.length);

            if (allRes.success) {
                const now = new Date();
                const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
                const upcoming = allRes.data.filter(l => {
                    if (l.status !== 'APPROVED') return false;
                    const start = new Date(l.startDate);
                    return start > today;
                });
                this.setText('leave-upcoming-count', upcoming.length);
                this.setText('leave-total-count', allRes.data.length);
            }
        } catch (e) {
            console.error('Failed to load leave summary:', e);
        }
    }

    async loadLeaves() {
        try {
            const response = await this.ajaxGet('/api/leaves');
            if (response.success) {
                this.renderLeavesTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load leaves:', e);
        }
    }

    renderLeavesTable(leaves) {
        const tbody = document.getElementById('leaves-table-body');
        if (!tbody) return;

        if (leaves.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('noLeaves', 'No leave records found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = leaves.map(leave => {
            const statusClass = 'badge-' + leave.status.toLowerCase().replace('_', '-');
            const statusKey = 'leaveStatus' + leave.status.charAt(0) + leave.status.slice(1).toLowerCase();
            const statusLabel = this.translate(statusKey, leave.status);
            const typeKey = 'leaveType' + this.formatLeaveTypeKey(leave.leaveType);
            const typeLabel = this.translate(typeKey, leave.leaveType);
            const daysText = leave.totalDays + ' ' + (leave.totalDays > 1 ? this.translate('leaveDays', 'days') : this.translate('leaveDay', 'day'));

            let actions = '';
            if (leave.status === 'PENDING') {
                actions = `
                    <button class="btn btn-sm btn-success btn-icon" onclick="app.approveLeave(${leave.id})" title="Approve"><i class="fas fa-check"></i></button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.rejectLeave(${leave.id})" title="Reject"><i class="fas fa-times"></i></button>
                `;
            } else if (leave.status === 'APPROVED') {
                actions = `
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.cancelLeave(${leave.id})" title="Cancel"><i class="fas fa-ban"></i></button>
                `;
            }
            actions += `<button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteLeave(${leave.id})" title="Delete"><i class="fas fa-trash"></i></button>`;

            return `
            <tr data-name="${leave.employeeName.toLowerCase()}" data-type="${leave.leaveType.toLowerCase()}">
                <td>${leave.employeeId}</td>
                <td>${leave.employeeName}</td>
                <td>${leave.department || '-'}</td>
                <td>${typeLabel}</td>
                <td>${this.formatDate(leave.startDate)}</td>
                <td>${this.formatDate(leave.endDate)}</td>
                <td>${daysText}</td>
                <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${leave.reason || ''}">${leave.reason || '-'}</td>
                <td><span class="badge ${statusClass}">${statusLabel}</span></td>
                <td>${actions}</td>
            </tr>
        `;
        }).join('');
    }

    formatLeaveTypeKey(type) {
        return type.charAt(0) + type.slice(1).toLowerCase();
    }

    async loadActiveLeaves() {
        try {
            const response = await this.ajaxGet('/api/leaves/active-today');
            if (response.success) {
                this.renderActiveLeaves(response.data);
            }
        } catch (e) {
            console.error('Failed to load active leaves:', e);
        }
    }

    renderActiveLeaves(leaves) {
        const tbody = document.getElementById('active-leaves-body');
        if (!tbody) return;

        if (leaves.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('noActive', 'No employees currently on leave')}</td></tr>`;
            return;
        }

        const today = new Date();
        today.setHours(0, 0, 0, 0);

        tbody.innerHTML = leaves.map(leave => {
            const endDate = new Date(leave.endDate);
            endDate.setHours(0, 0, 0, 0);
            const diffTime = endDate - today;
            const daysRemaining = Math.ceil(diffTime / (1000 * 60 * 60 * 24));
            const typeKey = 'leaveType' + this.formatLeaveTypeKey(leave.leaveType);
            const typeLabel = this.translate(typeKey, leave.leaveType);

            let remainingText;
            if (daysRemaining <= 0) {
                remainingText = this.translate('leaveLastDay', 'Last day');
            } else {
                remainingText = daysRemaining + ' ' + (daysRemaining > 1 ? this.translate('leaveDays', 'days') : this.translate('leaveDay', 'day'));
            }

            return `
            <tr>
                <td>${leave.employeeId}</td>
                <td>${leave.employeeName}</td>
                <td>${leave.department || '-'}</td>
                <td>${typeLabel}</td>
                <td>${this.formatDate(leave.startDate)}</td>
                <td>${this.formatDate(leave.endDate)}</td>
                <td><strong style="color:var(--hk-info);">${remainingText}</strong></td>
                <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${leave.reason || ''}">${leave.reason || '-'}</td>
            </tr>
        `;
        }).join('');
    }

    async loadPendingLeaves() {
        try {
            const response = await this.ajaxGet('/api/leaves/pending');
            if (response.success) {
                this.renderPendingLeaves(response.data);
            }
        } catch (e) {
            console.error('Failed to load pending leaves:', e);
        }
    }

    renderPendingLeaves(leaves) {
        const tbody = document.getElementById('pending-leaves-body');
        if (!tbody) return;

        if (leaves.length === 0) {
            tbody.innerHTML = `<tr><td colspan="9" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('noPending', 'No pending leave requests')}</td></tr>`;
            return;
        }

        tbody.innerHTML = leaves.map(leave => {
            const typeKey = 'leaveType' + this.formatLeaveTypeKey(leave.leaveType);
            const typeLabel = this.translate(typeKey, leave.leaveType);
            const daysText = leave.totalDays + ' ' + (leave.totalDays > 1 ? this.translate('leaveDays', 'days') : this.translate('leaveDay', 'day'));

            return `
            <tr>
                <td>${leave.employeeId}</td>
                <td>${leave.employeeName}</td>
                <td>${leave.department || '-'}</td>
                <td>${typeLabel}</td>
                <td>${this.formatDate(leave.startDate)}</td>
                <td>${this.formatDate(leave.endDate)}</td>
                <td>${daysText}</td>
                <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;" title="${leave.reason || ''}">${leave.reason || '-'}</td>
                <td>
                    <button class="btn btn-sm btn-success btn-icon" onclick="app.approveLeave(${leave.id})" title="Approve"><i class="fas fa-check"></i></button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.rejectLeave(${leave.id})" title="Reject"><i class="fas fa-times"></i></button>
                </td>
            </tr>
        `;
        }).join('');
    }

    async loadApprovedLeaves() {
        try {
            const response = await this.ajaxGet('/api/leaves/status/approved');
            if (response.success) {
                this.renderApprovedLeaves(response.data);
            }
        } catch (e) {
            console.error('Failed to load approved leaves:', e);
        }
    }

    renderApprovedLeaves(leaves) {
        const tbody = document.getElementById('approved-leaves-body');
        if (!tbody) return;

        if (leaves.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('noApproved', 'No approved leaves')}</td></tr>`;
            return;
        }

        tbody.innerHTML = leaves.map(leave => {
            const typeKey = 'leaveType' + this.formatLeaveTypeKey(leave.leaveType);
            const typeLabel = this.translate(typeKey, leave.leaveType);
            const daysText = leave.totalDays + ' ' + (leave.totalDays > 1 ? this.translate('leaveDays', 'days') : this.translate('leaveDay', 'day'));

            return `
            <tr>
                <td>${leave.employeeId}</td>
                <td>${leave.employeeName}</td>
                <td>${leave.department || '-'}</td>
                <td>${typeLabel}</td>
                <td>${this.formatDate(leave.startDate)}</td>
                <td>${this.formatDate(leave.endDate)}</td>
                <td>${daysText}</td>
                <td>${leave.approvedBy || '-'}</td>
            </tr>
        `;
        }).join('');
    }

    async populateEmployeeDropdown() {
        try {
            const response = await this.ajaxGet('/api/employees/active');
            if (response.success) {
                const select = document.getElementById('leave-employee');
                if (!select) return;
                // Keep the placeholder option
                const placeholder = select.querySelector('option[value=""]');
                select.innerHTML = '';
                if (placeholder) select.appendChild(placeholder);
                response.data.forEach(emp => {
                    const opt = document.createElement('option');
                    opt.value = emp.id;
                    opt.textContent = `${emp.employeeId} - ${this.displayName(emp)}`;
                    select.appendChild(opt);
                });
            }
        } catch (e) {
            console.error('Failed to load employees for dropdown:', e);
        }
    }

    openLeaveModal() {
        const modal = document.getElementById('leave-modal');
        document.getElementById('leave-id-hidden').value = '';
        document.getElementById('leave-employee').value = '';
        document.getElementById('leave-type').value = 'ANNUAL';
        document.getElementById('leave-start-date').value = '';
        document.getElementById('leave-end-date').value = '';
        document.getElementById('leave-reason').value = '';
        modal.classList.add('active');
    }

    async saveLeave() {
        const data = {
            employeeDbId: parseInt(document.getElementById('leave-employee').value),
            leaveType: document.getElementById('leave-type').value,
            startDate: document.getElementById('leave-start-date').value,
            endDate: document.getElementById('leave-end-date').value,
            reason: document.getElementById('leave-reason').value
        };

        try {
            const response = await this.ajaxPost('/api/leaves', data);
            if (response.success) {
                this.showToast(this.translate('leaveCreated', 'Leave request created'), 'success');
                this.closeModal('leave-modal');
                this.loadLeaves();
                this.loadPendingLeaves();
                this.loadLeaveSummary();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async approveLeave(id) {
        if (!confirm(this.translate('leaveApproveConfirm', 'Approve this leave request?'))) return;
        try {
            const response = await this.ajaxPost(`/api/leaves/${id}/approve`);
            if (response.success) {
                this.showToast(this.translate('leaveApproved', 'Leave approved'), 'success');
                this.refreshLeaveData();
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async rejectLeave(id) {
        if (!confirm(this.translate('leaveRejectConfirm', 'Reject this leave request?'))) return;
        try {
            const response = await this.ajaxPost(`/api/leaves/${id}/reject`);
            if (response.success) {
                this.showToast(this.translate('leaveRejected', 'Leave rejected'), 'success');
                this.refreshLeaveData();
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async cancelLeave(id) {
        try {
            const response = await this.ajaxPost(`/api/leaves/${id}/cancel`);
            if (response.success) {
                this.showToast(this.translate('leaveCancelled', 'Leave cancelled'), 'success');
                this.refreshLeaveData();
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async deleteLeave(id) {
        if (!confirm(this.translate('leaveDeleteConfirm', 'Delete this leave record?'))) return;
        try {
            const response = await this.ajaxDelete(`/api/leaves/${id}`);
            if (response.success) {
                this.showToast(this.translate('leaveDeleted', 'Leave deleted'), 'success');
                this.refreshLeaveData();
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    refreshLeaveData() {
        this.loadLeaves();
        this.loadActiveLeaves();
        this.loadPendingLeaves();
        this.loadApprovedLeaves();
        this.loadLeaveSummary();
    }

    filterLeaveTable(query) {
        const rows = document.querySelectorAll('#leaves-table-body tr');
        const lowerQuery = query.toLowerCase();
        rows.forEach(row => {
            const name = row.getAttribute('data-name') || '';
            const type = row.getAttribute('data-type') || '';
            row.style.display = (name.includes(lowerQuery) || type.includes(lowerQuery)) ? '' : 'none';
        });
    }

    formatDate(dateStr) {
        if (!dateStr) return '-';
        const date = new Date(dateStr);
        const locale = this.t.isRTL ? 'ku-Arab-IQ' : 'en-US';
        return date.toLocaleDateString(locale, { year: 'numeric', month: 'short', day: 'numeric' });
    }

    // ==================== GROUPS PAGE ====================
    initGroupsPage() {
        this.loadGroups();
        this.populateShiftDropdown();
        this.populateRuleDropdown();
        this.loadEmployeesForGroupModal();

        document.getElementById('btn-add-group')?.addEventListener('click', () => {
            this.openGroupModal();
        });

        document.getElementById('group-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveGroup();
        });

        const searchInput = document.getElementById('group-search');
        if (searchInput) {
            searchInput.addEventListener('input', (e) => {
                this.filterGroupTable(e.target.value);
            });
        }
    }

    async loadGroups() {
        try {
            const response = await this.ajaxGet('/api/groups');
            if (response.success) {
                this.renderGroupsTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load groups:', e);
        }
    }

    renderGroupsTable(groups) {
        const tbody = document.getElementById('groups-table-body');
        if (!tbody) return;

        if (groups.length === 0) {
            tbody.innerHTML = `<tr><td colspan="8" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('groups.noGroups', 'No groups found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = groups.map(g => `
            <tr data-name="${g.name.toLowerCase()}">
                <td>${g.id}</td>
                <td>${g.name}</td>
                <td>${g.description || '-'}</td>
                <td>${g.shiftName || '-'}</td>
                <td>${g.shiftRuleName || '-'}</td>
                <td>${g.employeeCount || 0}</td>
                <td>
                    <span class="badge ${g.isActive ? 'badge-present' : 'badge-absent'}">
                        ${g.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editGroup(${g.id})" title="${this.translate('edit', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteGroup(${g.id})" title="${this.translate('delete', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    async populateShiftDropdown() {
        try {
            const response = await this.ajaxGet('/api/shifts/active');
            if (response.success) {
                const select = document.getElementById('group-shift');
                if (!select) return;
                const placeholder = select.querySelector('option[value=""]');
                select.innerHTML = '';
                if (placeholder) select.appendChild(placeholder);
                response.data.forEach(shift => {
                    const opt = document.createElement('option');
                    opt.value = shift.id;
                    opt.textContent = `${shift.name} (${shift.startTime} - ${shift.endTime})`;
                    select.appendChild(opt);
                });
            }
        } catch (e) {
            console.error('Failed to load shifts for dropdown:', e);
        }
    }

    async populateRuleDropdown() {
        try {
            const response = await this.ajaxGet('/api/shift-rules/active');
            if (response.success) {
                const select = document.getElementById('group-rule');
                if (!select) return;
                const placeholder = select.querySelector('option[value=""]');
                select.innerHTML = '';
                if (placeholder) select.appendChild(placeholder);
                response.data.forEach(rule => {
                    const opt = document.createElement('option');
                    opt.value = rule.id;
                    opt.textContent = rule.name;
                    select.appendChild(opt);
                });
            }
        } catch (e) {
            console.error('Failed to load rules for dropdown:', e);
        }
    }

    async loadEmployeesForGroupModal() {
        try {
            const response = await this.ajaxGet('/api/employees/active');
            if (response.success) {
                this.groupModalEmployees = response.data;
            }
        } catch (e) {
            console.error('Failed to load employees for group modal:', e);
        }
    }

    renderGroupEmployeeCheckboxes(selectedIds = []) {
        const container = document.getElementById('group-employees-list');
        if (!container || !this.groupModalEmployees) return;

        if (this.groupModalEmployees.length === 0) {
            container.innerHTML = `<span style="color:var(--hk-text-muted);">${this.translate('groups.noEmployees', 'No employees available')}</span>`;
            return;
        }

        container.innerHTML = this.groupModalEmployees.map(emp => `
            <label style="display:flex;align-items:center;gap:8px;padding:4px 0;">
                <input type="checkbox" class="group-employee-check" value="${emp.id}" ${selectedIds.includes(emp.id) ? 'checked' : ''}>
                <span>${emp.employeeId} - ${this.displayName(emp)}</span>
            </label>
        `).join('');
    }

    openGroupModal(group = null) {
        const modal = document.getElementById('group-modal');
        const title = document.getElementById('group-modal-title');

        title.textContent = group
            ? this.translate('groups.modal.edit', 'Edit Group')
            : this.translate('groups.modal.add', 'Add Group');

        document.getElementById('group-id-hidden').value = group?.id || '';
        document.getElementById('group-name').value = group?.name || '';
        document.getElementById('group-description').value = group?.description || '';
        document.getElementById('group-shift').value = group?.shiftId || '';
        document.getElementById('group-rule').value = group?.shiftRuleId || '';

        this.renderGroupEmployeeCheckboxes(group?.employeeIds || []);

        modal.classList.add('active');
    }

    async saveGroup() {
        const id = document.getElementById('group-id-hidden').value;
        const selectedEmployeeIds = Array.from(document.querySelectorAll('.group-employee-check:checked'))
            .map(cb => parseInt(cb.value));

        const data = {
            name: document.getElementById('group-name').value,
            description: document.getElementById('group-description').value,
            shiftId: document.getElementById('group-shift').value ? parseInt(document.getElementById('group-shift').value) : null,
            shiftRuleId: document.getElementById('group-rule').value ? parseInt(document.getElementById('group-rule').value) : null,
            employeeIds: selectedEmployeeIds,
            isActive: true
        };

        try {
            const response = id
                ? await this.ajaxPut(`/api/groups/${id}`, data)
                : await this.ajaxPost('/api/groups', data);

            if (response.success) {
                this.showToast(
                    id ? this.translate('groups.updated', 'Group updated') : this.translate('groups.created', 'Group created'),
                    'success'
                );
                this.closeModal('group-modal');
                this.loadGroups();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async editGroup(id) {
        try {
            const response = await this.ajaxGet(`/api/groups/${id}`);
            if (response.success) {
                this.openGroupModal(response.data);
            }
        } catch (e) {
            this.showToast(this.translate('groups.loadFailed', 'Failed to load group'), 'error');
        }
    }

    async deleteGroup(id) {
        if (!confirm(this.translate('groups.deleteConfirm', 'Are you sure you want to delete this group?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/groups/${id}`);
            if (response.success) {
                this.showToast(this.translate('groups.deleted', 'Group deleted'), 'success');
                this.loadGroups();
            }
        } catch (e) {
            this.showToast(this.translate('groups.deleteFailed', 'Delete failed'), 'error');
        }
    }

    filterGroupTable(query) {
        const rows = document.querySelectorAll('#groups-table-body tr');
        const lowerQuery = query.toLowerCase();
        rows.forEach(row => {
            const name = row.getAttribute('data-name') || '';
            row.style.display = name.includes(lowerQuery) ? '' : 'none';
        });
    }

    // ==================== SHIFTS PAGE ====================
    initShiftsPage() {
        this.loadShifts();

        document.getElementById('btn-add-shift')?.addEventListener('click', () => {
            this.openShiftModal();
        });

        document.getElementById('shift-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveShift();
        });
    }

    async loadShifts() {
        try {
            const response = await this.ajaxGet('/api/shifts');
            if (response.success) {
                this.renderShiftsTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load shifts:', e);
        }
    }

    renderShiftsTable(shifts) {
        const tbody = document.getElementById('shifts-table-body');
        if (!tbody) return;

        if (shifts.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('shifts.noShifts', 'No shifts found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = shifts.map(s => {
            const days = s.workingDays ? s.workingDays.split(',').map(d => d.substring(0, 3)).join(', ') : '-';
            return `
            <tr>
                <td>${s.id}</td>
                <td>${s.name}</td>
                <td>${s.startTime || '-'}</td>
                <td>${s.endTime || '-'}</td>
                <td>${s.lateThresholdMinutes != null ? s.lateThresholdMinutes + ' min' : '-'}</td>
                <td>${s.earlyLeaveThresholdMinutes != null ? s.earlyLeaveThresholdMinutes + ' min' : '-'}</td>
                <td style="font-size:11px;">${days}</td>
                <td>${s.groupCount || 0}</td>
                <td>
                    <span class="badge ${s.isActive ? 'badge-present' : 'badge-absent'}">
                        ${s.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editShift(${s.id})" title="${this.translate('edit', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteShift(${s.id})" title="${this.translate('delete', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `;
        }).join('');
    }

    openShiftModal(shift = null) {
        const modal = document.getElementById('shift-modal');
        const title = document.getElementById('shift-modal-title');

        title.textContent = shift
            ? this.translate('shifts.modal.edit', 'Edit Shift')
            : this.translate('shifts.modal.add', 'Add Shift');

        document.getElementById('shift-id-hidden').value = shift?.id || '';
        document.getElementById('shift-name').value = shift?.name || '';
        document.getElementById('shift-description').value = shift?.description || '';
        document.getElementById('shift-start-time').value = shift?.startTime || '08:00';
        document.getElementById('shift-end-time').value = shift?.endTime || '17:00';
        document.getElementById('shift-late-threshold').value = shift?.lateThresholdMinutes ?? 15;
        document.getElementById('shift-early-leave').value = shift?.earlyLeaveThresholdMinutes ?? 15;
        document.getElementById('shift-break-start').value = shift?.breakStart || '';
        document.getElementById('shift-break-end').value = shift?.breakEnd || '';

        // Set working days checkboxes
        const workingDays = shift?.workingDays ? shift.workingDays.split(',') : ['MONDAY','TUESDAY','WEDNESDAY','THURSDAY','FRIDAY'];
        document.querySelectorAll('.working-day').forEach(cb => {
            cb.checked = workingDays.includes(cb.value);
        });

        modal.classList.add('active');
    }

    async saveShift() {
        const id = document.getElementById('shift-id-hidden').value;
        const workingDays = Array.from(document.querySelectorAll('.working-day:checked'))
            .map(cb => cb.value)
            .join(',');

        const data = {
            name: document.getElementById('shift-name').value,
            description: document.getElementById('shift-description').value,
            startTime: document.getElementById('shift-start-time').value,
            endTime: document.getElementById('shift-end-time').value,
            lateThresholdMinutes: parseInt(document.getElementById('shift-late-threshold').value) || 15,
            earlyLeaveThresholdMinutes: parseInt(document.getElementById('shift-early-leave').value) || 15,
            breakStart: document.getElementById('shift-break-start').value || null,
            breakEnd: document.getElementById('shift-break-end').value || null,
            workingDays: workingDays,
            isActive: true
        };

        try {
            const response = id
                ? await this.ajaxPut(`/api/shifts/${id}`, data)
                : await this.ajaxPost('/api/shifts', data);

            if (response.success) {
                this.showToast(
                    id ? this.translate('shifts.updated', 'Shift updated') : this.translate('shifts.created', 'Shift created'),
                    'success'
                );
                this.closeModal('shift-modal');
                this.loadShifts();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async editShift(id) {
        try {
            const response = await this.ajaxGet(`/api/shifts/${id}`);
            if (response.success) {
                this.openShiftModal(response.data);
            }
        } catch (e) {
            this.showToast(this.translate('shifts.loadFailed', 'Failed to load shift'), 'error');
        }
    }

    async deleteShift(id) {
        if (!confirm(this.translate('shifts.deleteConfirm', 'Are you sure you want to delete this shift?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/shifts/${id}`);
            if (response.success) {
                this.showToast(this.translate('shifts.deleted', 'Shift deleted'), 'success');
                this.loadShifts();
            }
        } catch (e) {
            this.showToast(this.translate('shifts.deleteFailed', 'Delete failed'), 'error');
        }
    }

    // ==================== SHIFT RULES PAGE ====================
    initShiftRulesPage() {
        this.loadShiftRules();

        document.getElementById('btn-add-rule')?.addEventListener('click', () => {
            this.openRuleModal();
        });

        document.getElementById('rule-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveRule();
        });
    }

    async loadShiftRules() {
        try {
            const response = await this.ajaxGet('/api/shift-rules');
            if (response.success) {
                this.renderRulesTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load shift rules:', e);
        }
    }

    renderRulesTable(rules) {
        const tbody = document.getElementById('rules-table-body');
        if (!tbody) return;

        if (rules.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('shiftRules.noRules', 'No shift rules found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = rules.map(r => `
            <tr>
                <td>${r.id}</td>
                <td>${r.name}</td>
                <td>${r.lateThresholdMinutes != null ? r.lateThresholdMinutes + ' min' : '-'}</td>
                <td>${r.earlyLeaveThresholdMinutes != null ? r.earlyLeaveThresholdMinutes + ' min' : '-'}</td>
                <td>${r.absentAfterMinutes != null ? r.absentAfterMinutes + ' min' : '-'}</td>
                <td>${r.gracePeriodMinutes != null ? r.gracePeriodMinutes + ' min' : '-'}</td>
                <td>${r.groupCount || 0}</td>
                <td>${r.scheduleCount || 0}</td>
                <td>
                    <span class="badge ${r.isActive ? 'badge-present' : 'badge-absent'}">
                        ${r.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editRule(${r.id})" title="${this.translate('edit', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteRule(${r.id})" title="${this.translate('delete', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    openRuleModal(rule = null) {
        const modal = document.getElementById('rule-modal');
        const title = document.getElementById('rule-modal-title');

        title.textContent = rule
            ? this.translate('shiftRules.modal.edit', 'Edit Rule')
            : this.translate('shiftRules.modal.add', 'Add Rule');

        document.getElementById('rule-id-hidden').value = rule?.id || '';
        document.getElementById('rule-name').value = rule?.name || '';
        document.getElementById('rule-description').value = rule?.description || '';
        document.getElementById('rule-late-threshold').value = rule?.lateThresholdMinutes ?? 15;
        document.getElementById('rule-early-leave').value = rule?.earlyLeaveThresholdMinutes ?? 15;
        document.getElementById('rule-absent-after').value = rule?.absentAfterMinutes ?? 240;
        document.getElementById('rule-grace-period').value = rule?.gracePeriodMinutes ?? 0;
        document.getElementById('rule-max-hours').value = rule?.maxWorkHours ?? '';
        document.getElementById('rule-require-checkin').value = rule?.requireCheckIn !== false ? 'true' : 'false';

        modal.classList.add('active');
    }

    async saveRule() {
        const id = document.getElementById('rule-id-hidden').value;
        const maxHours = document.getElementById('rule-max-hours').value;
        const data = {
            name: document.getElementById('rule-name').value,
            description: document.getElementById('rule-description').value,
            lateThresholdMinutes: parseInt(document.getElementById('rule-late-threshold').value) || 15,
            earlyLeaveThresholdMinutes: parseInt(document.getElementById('rule-early-leave').value) || 15,
            absentAfterMinutes: parseInt(document.getElementById('rule-absent-after').value) || 240,
            gracePeriodMinutes: parseInt(document.getElementById('rule-grace-period').value) || 0,
            maxWorkHours: maxHours ? parseFloat(maxHours) : null,
            requireCheckIn: document.getElementById('rule-require-checkin').value === 'true',
            requireCheckOut: true,
            isActive: true
        };

        try {
            const response = id
                ? await this.ajaxPut(`/api/shift-rules/${id}`, data)
                : await this.ajaxPost('/api/shift-rules', data);

            if (response.success) {
                this.showToast(
                    id ? this.translate('shiftRules.updated', 'Rule updated') : this.translate('shiftRules.created', 'Rule created'),
                    'success'
                );
                this.closeModal('rule-modal');
                this.loadShiftRules();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async editRule(id) {
        try {
            const response = await this.ajaxGet(`/api/shift-rules/${id}`);
            if (response.success) {
                this.openRuleModal(response.data);
            }
        } catch (e) {
            this.showToast(this.translate('shiftRules.loadFailed', 'Failed to load rule'), 'error');
        }
    }

    async deleteRule(id) {
        if (!confirm(this.translate('shiftRules.deleteConfirm', 'Are you sure you want to delete this rule?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/shift-rules/${id}`);
            if (response.success) {
                this.showToast(this.translate('shiftRules.deleted', 'Rule deleted'), 'success');
                this.loadShiftRules();
            }
        } catch (e) {
            this.showToast(this.translate('shiftRules.deleteFailed', 'Delete failed'), 'error');
        }
    }

    // ==================== SHIFT SCHEDULE PAGE ====================
    initShiftSchedulePage() {
        this.loadShiftSchedules();
        this.populateScheduleDropdowns();

        document.getElementById('btn-add-schedule')?.addEventListener('click', () => {
            this.openScheduleModal();
        });

        document.getElementById('schedule-form')?.addEventListener('submit', (e) => {
            e.preventDefault();
            this.saveSchedule();
        });
    }

    async loadShiftSchedules() {
        try {
            const response = await this.ajaxGet('/api/shift-schedules');
            if (response.success) {
                this.renderSchedulesTable(response.data);
            }
        } catch (e) {
            console.error('Failed to load shift schedules:', e);
        }
    }

    renderSchedulesTable(schedules) {
        const tbody = document.getElementById('schedules-table-body');
        if (!tbody) return;

        if (schedules.length === 0) {
            tbody.innerHTML = `<tr><td colspan="10" style="text-align:center;color:var(--hk-text-muted);padding:30px;">${this.translate('shiftSchedule.noSchedules', 'No shift schedules found')}</td></tr>`;
            return;
        }

        tbody.innerHTML = schedules.map(s => `
            <tr>
                <td>${s.id}</td>
                <td>${s.groupName || '-'}</td>
                <td>${s.shiftName || '-'} (${s.shiftStartTime || ''} - ${s.shiftEndTime || ''})</td>
                <td>${s.ruleName || '-'}</td>
                <td>${this.formatDate(s.startDate)}</td>
                <td>${this.formatDate(s.endDate)}</td>
                <td>${s.employeeCount || 0}</td>
                <td>
                    ${s.currentlyActive
                        ? `<span class="badge badge-present">${this.translate('shiftSchedule.active', 'Active')}</span>`
                        : `<span class="badge badge-absent">${this.translate('shiftSchedule.inactive', 'Inactive')}</span>`}
                </td>
                <td>
                    <span class="badge ${s.isActive ? 'badge-present' : 'badge-absent'}">
                        ${s.isActive ? this.translate('active', 'Active') : this.translate('inactive', 'Inactive')}
                    </span>
                </td>
                <td>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.editSchedule(${s.id})" title="${this.translate('edit', 'Edit')}">
                        <i class="fas fa-edit"></i>
                    </button>
                    <button class="btn btn-sm btn-secondary btn-icon" onclick="app.deleteSchedule(${s.id})" title="${this.translate('delete', 'Delete')}">
                        <i class="fas fa-trash"></i>
                    </button>
                </td>
            </tr>
        `).join('');
    }

    async populateScheduleDropdowns() {
        try {
            const [groupsRes, shiftsRes, rulesRes] = await Promise.all([
                this.ajaxGet('/api/groups/active'),
                this.ajaxGet('/api/shifts/active'),
                this.ajaxGet('/api/shift-rules/active')
            ]);

            if (groupsRes.success) {
                const select = document.getElementById('schedule-group');
                if (select) {
                    const placeholder = select.querySelector('option[value=""]');
                    select.innerHTML = '';
                    if (placeholder) select.appendChild(placeholder);
                    groupsRes.data.forEach(g => {
                        const opt = document.createElement('option');
                        opt.value = g.id;
                        opt.textContent = g.name;
                        select.appendChild(opt);
                    });
                }
            }

            if (shiftsRes.success) {
                const select = document.getElementById('schedule-shift');
                if (select) {
                    const placeholder = select.querySelector('option[value=""]');
                    select.innerHTML = '';
                    if (placeholder) select.appendChild(placeholder);
                    shiftsRes.data.forEach(s => {
                        const opt = document.createElement('option');
                        opt.value = s.id;
                        opt.textContent = `${s.name} (${s.startTime} - ${s.endTime})`;
                        select.appendChild(opt);
                    });
                }
            }

            if (rulesRes.success) {
                const select = document.getElementById('schedule-rule');
                if (select) {
                    const placeholder = select.querySelector('option[value=""]');
                    select.innerHTML = '';
                    if (placeholder) select.appendChild(placeholder);
                    rulesRes.data.forEach(r => {
                        const opt = document.createElement('option');
                        opt.value = r.id;
                        opt.textContent = r.name;
                        select.appendChild(opt);
                    });
                }
            }
        } catch (e) {
            console.error('Failed to load dropdown data:', e);
        }
    }

    openScheduleModal(schedule = null) {
        const modal = document.getElementById('schedule-modal');
        const title = document.getElementById('schedule-modal-title');

        title.textContent = schedule
            ? this.translate('shiftSchedule.modal.edit', 'Edit Schedule')
            : this.translate('shiftSchedule.modal.add', 'Add Schedule');

        document.getElementById('schedule-id-hidden').value = schedule?.id || '';
        document.getElementById('schedule-group').value = schedule?.groupId || '';
        document.getElementById('schedule-shift').value = schedule?.shiftId || '';
        document.getElementById('schedule-rule').value = schedule?.ruleId || '';
        document.getElementById('schedule-start-date').value = schedule?.startDate || '';
        document.getElementById('schedule-end-date').value = schedule?.endDate || '';
        document.getElementById('schedule-notes').value = schedule?.notes || '';

        modal.classList.add('active');
    }

    async saveSchedule() {
        const id = document.getElementById('schedule-id-hidden').value;
        const data = {
            groupId: parseInt(document.getElementById('schedule-group').value),
            shiftId: parseInt(document.getElementById('schedule-shift').value),
            ruleId: document.getElementById('schedule-rule').value ? parseInt(document.getElementById('schedule-rule').value) : null,
            startDate: document.getElementById('schedule-start-date').value,
            endDate: document.getElementById('schedule-end-date').value,
            notes: document.getElementById('schedule-notes').value,
            isActive: true
        };

        try {
            const response = id
                ? await this.ajaxPut(`/api/shift-schedules/${id}`, data)
                : await this.ajaxPost('/api/shift-schedules', data);

            if (response.success) {
                this.showToast(
                    id ? this.translate('shiftSchedule.updated', 'Schedule updated') : this.translate('shiftSchedule.created', 'Schedule created'),
                    'success'
                );
                this.closeModal('schedule-modal');
                this.loadShiftSchedules();
            } else {
                this.showToast(response.message, 'error');
            }
        } catch (e) {
            this.showToast(this.translate('operationFailed', 'Operation failed'), 'error');
        }
    }

    async editSchedule(id) {
        try {
            const response = await this.ajaxGet(`/api/shift-schedules/${id}`);
            if (response.success) {
                this.openScheduleModal(response.data);
            }
        } catch (e) {
            this.showToast(this.translate('shiftSchedule.loadFailed', 'Failed to load schedule'), 'error');
        }
    }

    async deleteSchedule(id) {
        if (!confirm(this.translate('shiftSchedule.deleteConfirm', 'Are you sure you want to delete this schedule?'))) return;

        try {
            const response = await this.ajaxDelete(`/api/shift-schedules/${id}`);
            if (response.success) {
                this.showToast(this.translate('shiftSchedule.deleted', 'Schedule deleted'), 'success');
                this.loadShiftSchedules();
            }
        } catch (e) {
            this.showToast(this.translate('shiftSchedule.deleteFailed', 'Delete failed'), 'error');
        }
    }

    // ==================== UTILITIES ====================
    initNavigation() {
        const currentPath = window.location.pathname;
        document.querySelectorAll('.nav-item').forEach(item => {
            const href = item.getAttribute('href');
            if (href === currentPath || (currentPath === '/' && href === '/')) {
                item.classList.add('active');
            }
        });
    }

    initTooltips() {
        // Simple tooltip implementation
        document.querySelectorAll('[title]').forEach(el => {
            el.addEventListener('mouseenter', (e) => {
                // Tooltip logic can be expanded
            });
        });
    }

    startSyncIndicator() {
        const dot = document.getElementById('sync-dot');
        if (dot) {
            setInterval(() => {
                dot.classList.add('syncing');
                setTimeout(() => dot.classList.remove('syncing'), 500);
            }, 2000);
        }
    }

    refreshCurrentData() {
        if (this.currentPage === '/' || this.currentPage === '/dashboard') {
            this.loadDashboardStats();
            this.loadRecentDetections();
        } else if (this.currentPage === '/attendance') {
            this.loadAttendanceRecords();
        }
    }

    formatTime(dateTimeStr) {
        if (!dateTimeStr) return '-';
        const date = new Date(dateTimeStr);
        const locale = this.t.isRTL ? 'ku-Arab-IQ' : 'en-US';
        return date.toLocaleTimeString(locale, { hour: '2-digit', minute: '2-digit', second: '2-digit' });
    }

    showToast(message, type = 'info') {
        const toast = document.createElement('div');
        toast.className = `alert alert-${type} show`;
        const isRTL = this.t.isRTL;
        toast.style.cssText = isRTL
            ? 'position:fixed;top:20px;left:20px;z-index:9999;min-width:250px;'
            : 'position:fixed;top:20px;right:20px;z-index:9999;min-width:250px;';
        toast.textContent = message;
        document.body.appendChild(toast);

        setTimeout(() => {
            toast.remove();
        }, 3000);
    }

    closeModal(modalId) {
        document.getElementById(modalId)?.classList.remove('active');
    }
}

// Close modals on overlay click
document.addEventListener('click', (e) => {
    if (e.target.classList.contains('modal-overlay')) {
        e.target.classList.remove('active');
    }
});

// Initialize app
const app = new AttendanceApp();
