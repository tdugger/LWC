package com.griefcraft.roles;

import com.griefcraft.ProtectionAccess;
import com.griefcraft.entity.Player;
import com.griefcraft.model.Protection;
import com.griefcraft.model.Role;

public class PlayerRole extends Role {

    public PlayerRole(Protection protection, String roleName, ProtectionAccess roleAccess) {
        super(protection, roleName, roleAccess);
    }

    @Override
    public int getId() {
        return 1; // adapted from LWCv4
    }

    public ProtectionAccess getAccess(Protection protection, Player player) {
        return player.getName().equalsIgnoreCase(getRoleName()) ? getRoleAccess() : ProtectionAccess.NONE;
    }

}