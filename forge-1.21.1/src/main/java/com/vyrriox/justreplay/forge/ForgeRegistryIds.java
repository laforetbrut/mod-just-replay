package com.vyrriox.justreplay.forge;

import com.vyrriox.justreplay.format.RegistryIdMap;
import com.vyrriox.justreplay.format.RegistryIdsBackend;

import java.util.Map;

/**
 * Registry ids on Forge. Not wired yet: nothing is saved in a recording and nothing is applied, so a replay keeps the ids
 * of this game.
 *
 * @author vyrriox
 */
final class ForgeRegistryIds implements RegistryIdsBackend {
    @Override
    public byte[] encode() {
        return null;
    }

    @Override
    public RegistryIdMap decode(byte[] bytes) {
        return current();
    }

    @Override
    public RegistryIdMap current() {
        return new RegistryIdMap(Map.of());
    }

    @Override
    public boolean apply(RegistryIdMap ids) {
        return false;
    }

    @Override
    public void selfCheck(CheckLog log) {
        log.log("registry ids not wired on this loader");
    }
}
