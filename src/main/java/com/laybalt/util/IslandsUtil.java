package com.laybalt.util;

import java.util.Arrays;

public class IslandsUtil {
    // List of possible island types with display names
    public enum IslandType {
        SINGLE_PLAYER("Singleplayer"),
        PRIVATE_ISLAND("Private Island"),
        GARDEN("The Garden"),
        SPIDER_DEN("Spider's Den"),
        CRIMSON_ISLE("Crimson Isle"),
        THE_END("The End"),
        GOLD_MINE("Gold Mine"),
        DEEP_CAVERNS("Deep Caverns"),
        DWARVEN_MINES("Dwarven Mines"),
        CRYSTAL_HOLLOWS("Crystal Hollows"),
        FARMING_ISLAND("The Farming Islands"),
        THE_PARK("The Park"),
        DUNGEON("Catacombs"),
        DUNGEON_HUB("Dungeon Hub"),
        HUB("Hub"),
        DARK_AUCTION("Dark Auction"),
        JERRY_WORKSHOP("Jerry's Workshop"),
        KUUDRA("Kuudra"),
        UNKNOWN("(Unknown)");

        private final String displayName;

        IslandType(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isArea(IslandType... areas) {
            return Arrays.asList(areas).contains(this);
        }
    }
}   