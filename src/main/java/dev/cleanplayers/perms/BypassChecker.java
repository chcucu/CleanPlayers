package dev.cleanplayers.perms;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Best-effort offline permission check for {@code cleanplayers.bypass}.
 *
 * <p>Bukkit cannot evaluate permissions of offline players, so LuckPerms is queried through
 * reflection when it is installed. Only users already present in LuckPerms' cache are available;
 * this is intentional to avoid blocking network/database calls for every scanned player. The
 * reliable way to protect a player who is not currently cached is {@code /cleanplayers protect add}.</p>
 */
public final class BypassChecker {

    private static final String NODE = "cleanplayers.bypass";

    private final Logger logger;
    private volatile boolean warned;

    public BypassChecker(Logger logger) {
        this.logger = logger;
    }

    public boolean isBypassed(UUID uuid) {
        if (uuid == null) {
            return false;
        }
        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            if (luckPerms == null) {
                return false;
            }
            Object userManager = findNoArg(luckPerms, "getUserManager").invoke(luckPerms);
            Method getUser = findOneArg(userManager, "getUser", UUID.class);
            if (getUser == null) {
                return false;
            }
            Object user = getUser.invoke(userManager, uuid);
            if (user == null) {
                return false;
            }
            Object cachedData = findNoArg(user, "getCachedData").invoke(user);
            Object permissionData = findNoArg(cachedData, "getPermissionData").invoke(cachedData);
            Method check = findOneArg(permissionData, "checkPermission", String.class);
            if (check == null) {
                return false;
            }
            Object tristate = check.invoke(permissionData, NODE);
            return tristate != null && "TRUE".equalsIgnoreCase(String.valueOf(
                    tristate.getClass().getMethod("asBoolean").invoke(tristate)));
        } catch (ClassNotFoundException ex) {
            return false; // LuckPerms not installed.
        } catch (ReflectiveOperationException | LinkageError ex) {
            if (!warned) {
                warned = true;
                logger.log(Level.WARNING,
                        "Could not evaluate cleanplayers.bypass through LuckPerms; only cached users "
                                + "can be checked. Use /cleanplayers protect add for guaranteed protection.", ex);
            }
            return false;
        }
    }

    private static Method findNoArg(Object target, String name) throws NoSuchMethodException {
        return target.getClass().getMethod(name);
    }

    private static Method findOneArg(Object target, String name, Class<?> type) {
        for (Method method : target.getClass().getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].equals(type)) {
                return method;
            }
        }
        return null;
    }
}
