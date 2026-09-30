package dev.localsoul.aero.game.protocol;

import io.netty.buffer.ByteBuf;

/**
 * Ein Stat-Wert in {@code ObjectStatusData} (AS3 {@code StatData}):
 * {@code statType} (Byte), dann {@code int} oder {@code UTF} je nach Typ.
 */
public final class StatData {

    /** String-Stat-Typen (aus {@code StatData.isStringStat}). */
    public static final int NAME_STAT = 31;
    public static final int ACCOUNT_ID_STAT = 38;
    public static final int OWNER_ACCOUNT_ID_STAT = 54;
    public static final int GUILD_NAME_STAT = 62;
    public static final int PET_NAME_STAT = 82;

    public int statType;
    public int statValue;
    public String strStatValue = "";

    private boolean isStringStat() {
        return switch (statType) {
            case NAME_STAT, GUILD_NAME_STAT, PET_NAME_STAT,
                    ACCOUNT_ID_STAT, OWNER_ACCOUNT_ID_STAT -> true;
            default -> false;
        };
    }

    public void writeToOutput(final ByteBuf buf) {
        buf.writeByte(statType);
        if (isStringStat()) {
            ByteBufs.writeUTF(buf, strStatValue);
        } else {
            buf.writeInt(statValue);
        }
    }

    public static StatData of(final int type, final int value) {
        final StatData stat = new StatData();
        stat.statType = type;
        stat.statValue = value;
        return stat;
    }

    public static StatData of(final int type, final String value) {
        final StatData stat = new StatData();
        stat.statType = type;
        stat.strStatValue = value;
        return stat;
    }
}