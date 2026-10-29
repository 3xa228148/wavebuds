# Runtime permissions are granted on boot by service.sh; nothing to do at install time.
set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/service.sh" 0 0 0755
