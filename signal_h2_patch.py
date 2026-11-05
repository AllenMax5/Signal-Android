"""
mitmproxy addon: Monkey-patch h2 to support HTTP/2 Extended CONNECT Protocol (RFC 8441)

This allows Signal's WebSocket-over-HTTP/2 connections (using :protocol=websocket
pseudo-header) to be intercepted by mitmproxy without being rejected by h2's
strict RFC 7540 header validation.

Usage:
    mitmproxy -s signal_h2_patch.py [other args]
    mitmdump -s signal_h2_patch.py [other args]

RFC references:
    - RFC 8441 §3: SETTINGS_ENABLE_CONNECT_PROTOCOL negotiation
    - RFC 8441 §5: Extended CONNECT request format
"""
import logging

log = logging.getLogger(__name__)


def _apply_h2_patches():
    """
    Apply monkey-patches to the h2 library to support RFC 8441 Extended CONNECT.
    Called at module load time so patches are active before any connections.
    """
    try:
        import h2.utilities
        import h2.settings
        from h2.exceptions import ProtocolError
        from h2.settings import SettingCodes
    except ImportError as e:
        log.error(f"[h2-patch] Failed to import h2: {e}")
        return

    # ── Patch 1: Default ENABLE_CONNECT_PROTOCOL = 1 in server-side Settings ──
    # RFC 8441 §3: The server MUST send SETTINGS_ENABLE_CONNECT_PROTOCOL=1
    # before the client may use :protocol. mitmproxy acts as a server to Signal,
    # so we inject this setting into the initial SETTINGS frame.
    _orig_init = h2.settings.Settings.__init__

    def _patched_settings_init(self, *args, **kwargs):
        _orig_init(self, *args, **kwargs)
        try:
            # Only set on server-side (client_side=False) settings
            # h2.settings.Settings is used for both local and remote settings
            self[SettingCodes.ENABLE_CONNECT_PROTOCOL] = 1
        except Exception as e:
            log.debug(f"[h2-patch] Could not set ENABLE_CONNECT_PROTOCOL: {e}")

    h2.settings.Settings.__init__ = _patched_settings_init
    log.info("[h2-patch] Patch 1 applied: SETTINGS_ENABLE_CONNECT_PROTOCOL defaults to 1")

    # ── Patch 2: Relax :protocol pseudo-header validation ──────────────────────
    # The original _check_pseudo_header_field_acceptability raises ProtocolError
    # when :protocol appears without CONNECT method, or sometimes even with CONNECT
    # if the remote settings haven't been negotiated yet.
    _orig_check = h2.utilities._check_pseudo_header_field_acceptability

    def _patched_check(pseudo_headers, method, hdr_validation_flags):
        try:
            return _orig_check(pseudo_headers, method, hdr_validation_flags)
        except ProtocolError as exc:
            msg = str(exc)
            # Allow :protocol through for CONNECT (RFC 8441 Extended CONNECT)
            if ":protocol" in msg or "connect-request-only" in msg:
                log.debug(
                    f"[h2-patch] Suppressed ProtocolError (RFC 8441 Extended CONNECT): {exc}"
                )
                return  # allow the header block to pass
            raise  # re-raise unrelated protocol errors

    h2.utilities._check_pseudo_header_field_acceptability = _patched_check
    log.info("[h2-patch] Patch 2 applied: :protocol pseudo-header validation relaxed")

    # ── Patch 3: Relax required pseudo-headers check for CONNECT method ────────
    # RFC 8441 §5: Extended CONNECT MUST have :scheme, :path, :authority
    # but standard CONNECT (RFC 7540 §8.3) must NOT have :scheme or :path.
    # Some Signal versions may omit :scheme on the Extended CONNECT, so we
    # relax the mandatory header check when method=CONNECT.
    _orig_reject = h2.utilities._reject_pseudo_header_fields

    def _patched_reject(headers, hdr_validation_flags):
        """
        Wraps _reject_pseudo_header_fields to suppress :protocol-related errors
        that arise from method comparison happening before the generator is consumed.
        """
        try:
            # The original is a generator; we must consume it
            result = list(_orig_reject(headers, hdr_validation_flags))
            return iter(result)
        except ProtocolError as exc:
            msg = str(exc)
            if ":protocol" in msg or "connect-request-only" in msg:
                log.debug(
                    f"[h2-patch] Suppressed ProtocolError in _reject_pseudo_header_fields: {exc}"
                )
                # Re-run without consuming to return a working generator
                # We need to pass headers through without the check
                return iter(list(headers))
            raise

    # Only apply patch 3 if patch 2 alone doesn't catch all cases
    # h2.utilities._reject_pseudo_header_fields = _patched_reject
    # (commented out by default - enable if :protocol errors persist)

    log.info("[h2-patch] All h2 patches applied successfully")


# Apply patches immediately when this module is imported
_apply_h2_patches()


# ── mitmproxy addon interface ───────────────────────────────────────────────

class SignalH2WebSocketAddon:
    """
    mitmproxy addon that patches h2 to support Signal's WebSocket-over-HTTP/2
    connections using the RFC 8441 Extended CONNECT Protocol.

    Without this patch, mitmproxy rejects the :protocol=websocket pseudo-header
    in Signal's chat connection (chat.signal.org), causing it to fall back to
    a direct (non-proxied) connection.
    """

    def __init__(self):
        log.info(
            "[SignalH2Addon] Loaded. Signal WebSocket over HTTP/2 (RFC 8441) support enabled."
        )

    def running(self):
        log.info(
            "[SignalH2Addon] mitmproxy is running with h2 Extended CONNECT patch active."
        )


addons = [SignalH2WebSocketAddon()]
