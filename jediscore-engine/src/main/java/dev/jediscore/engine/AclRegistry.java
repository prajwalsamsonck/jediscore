package dev.jediscore.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The ACL user table. Always contains the {@code default} user; additional users
 * are created by {@code ACL SETUSER}.
 *
 * <p>Command-thread-confined (touched by {@code ACL}/{@code AUTH} and read by the
 * dispatcher's permission check, all on the command thread).
 */
public final class AclRegistry {

    private final Map<String, AclUser> users = new LinkedHashMap<>();

    /**
     * Creates the registry with a {@code default} superuser, seeded from
     * {@code requirepass} (a password makes it require AUTH; no password makes it
     * {@code nopass}).
     *
     * @param requirepass the optional password
     */
    public AclRegistry(String requirepass) {
        AclUser def = new AclUser("default");
        def.makeSuperuser(requirepass == null || requirepass.isEmpty());
        if (requirepass != null && !requirepass.isEmpty()) {
            def.setPlainPassword(requirepass);
        }
        users.put("default", def);
    }

    /** @return the {@code default} user */
    public AclUser defaultUser() {
        return users.get("default");
    }

    /**
     * @param name the username
     * @return the user, or {@code null} if unknown
     */
    public AclUser user(String name) {
        return users.get(name);
    }

    /**
     * Returns the user, creating a disabled one if it does not exist.
     *
     * @param name the username
     * @return the user
     */
    public AclUser getOrCreate(String name) {
        return users.computeIfAbsent(name, AclUser::new);
    }

    /**
     * Deletes a user (the {@code default} user cannot be deleted).
     *
     * @param name the username
     * @return {@code true} if a user was removed
     */
    public boolean delete(String name) {
        if ("default".equals(name)) {
            return false;
        }
        return users.remove(name) != null;
    }

    /** @return all users, default first */
    public List<AclUser> all() {
        return new ArrayList<>(users.values());
    }

    /** @return the usernames */
    public List<String> usernames() {
        return new ArrayList<>(users.keySet());
    }

    /**
     * @return whether a new connection must {@code AUTH} before running commands
     *         (i.e. the default user is not an enabled {@code nopass} user)
     */
    public boolean authRequired() {
        AclUser def = defaultUser();
        return !(def.isEnabled() && def.isNopass());
    }

    /**
     * Authenticates a username/password pair.
     *
     * @param username the username
     * @param password the password
     * @return the authenticated user, or {@code null} on failure
     */
    public AclUser authenticate(String username, String password) {
        AclUser user = users.get(username);
        if (user == null || !user.isEnabled() || !user.checkPassword(password)) {
            return null;
        }
        return user;
    }
}
