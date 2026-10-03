package io.github.krekerdm.baritonebots.common.msg;

/** {@code {"item":glob,"count":int}} entry of {@code take}/{@code drop}/{@code transfer} args; count -1 = all. */
public record ItemCount(String item, int count) {
    public static final int ALL = -1;

    public boolean all() {
        return count < 0;
    }
}
