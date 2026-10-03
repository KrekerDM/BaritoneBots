package io.github.krekerdm.baritonebots.common.msg;

import java.util.List;

/** {@link Query#kind()} values (SPEC §2.4). */
public final class QueryKinds {
    public static final String INVENTORY = "inventory";
    public static final String ENTITIES = "entities";
    public static final String CONTAINERS_NEARBY = "containers_nearby";
    public static final String BOM = "bom";
    public static final String PROGRESS = "progress";
    public static final String BLOCK_AT = "block_at";
    public static final String PLAYER = "player";
    public static final String RECIPE_BOOK = "recipe_book";

    public static final List<String> ALL = List.of(INVENTORY, ENTITIES, CONTAINERS_NEARBY, BOM, PROGRESS, BLOCK_AT,
            PLAYER, RECIPE_BOOK);

    private QueryKinds() {
    }
}
