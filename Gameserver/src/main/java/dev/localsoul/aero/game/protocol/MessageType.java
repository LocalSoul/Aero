package dev.localsoul.aero.game.protocol;

/**
 * Message-Typ-IDs des 27.7.X2-Protokolls (aus
 * {@code GameServerConnection.as} extrahiert). Enthält den vollständigen
 * ID-Satz; implementiert sind in V1 nur die Demo-Pakete (§4 der
 * gameserver_implement.md).
 */
public enum MessageType {

    FAILURE(0),
    SERVERPLAYERSHOOT(1),
    USEITEM(3),
    QUESTOBJID(4),
    TELEPORT(5),
    OTHERHIT(6),
    AOE(7),
    PING(8),
    PLAYERTEXT(9),
    SHOOTACK(10),
    CREATEGUILD(11),
    DEATH(12),
    RESKIN_UNLOCK(13),
    INVITEDTOGUILD(14),
    ACCEPT_ARENA_DEATH(15),
    ESCAPE(16),
    PLAYSOUND(17),
    INVRESULT(18),
    KEY_INFO_RESPONSE(19),
    NOTIFICATION(20),
    PETYARDUPDATE(21),
    CANCELTRADE(22),
    USEPORTAL(23),
    MOVE(24),
    CHOOSENAME(25),
    ACCEPTTRADE(26),
    CHECKCREDITS(27),
    MAPINFO(28),
    HATCH_PET(30),
    NEWTICK(31),
    FILE(33),
    TEXT(34),
    TRADEDONE(35),
    SETCONDITION(36),
    PLAYERHIT(37),
    TRADECHANGED(38),
    ACTIVEPETUPDATE(39),
    GLOBAL_NOTIFICATION(40),
    PLAYERSHOOT(41),
    PET_CHANGE_FORM_MSG(42),
    UPDATE(44),
    ENTER_ARENA(45),
    RESKIN(46),
    ACTIVE_PET_UPDATE_REQUEST(47),
    CREATE(48),
    ALLYSHOOT(49),
    DELETE_PET(50),
    TRADEREQUESTED(51),
    DAMAGE(52),
    ACCOUNTLIST(53),
    ARENA_DEATH(55),
    BUYRESULT(56),
    CLIENTSTAT(57),
    CREATE_SUCCESS(58),
    SQUAREHIT(59),
    QUEST_FETCH_RESPONSE(60),
    PASSWORD_PROMPT(61),
    NAMERESULT(62),
    LOAD(63),
    INVSWAP(64),
    IMMINENT_ARENA_WAVE(65),
    KEY_INFO_REQUEST(66),
    JOINGUILD(67),
    RECONNECT(68),
    EVOLVE_PET(69),
    TRADESTART(74),
    GUILDREMOVE(75),
    NEW_ABILITY(76),
    BUY(77),
    SHOWEFFECT(78),
    PETUPGRADEREQUEST(79),
    VERIFY_EMAIL(80),
    CHANGEGUILDRANK(81),
    REQUESTTRADE(82),
    PONG(83),
    GROUNDDAMAGE(84),
    GUILDINVITE(85),
    HELLO(86),
    EDITACCOUNTLIST(87),
    PIC(88),
    AOEACK(89),
    ENEMYSHOOT(90),
    QUEST_FETCH_ASK(91),
    GOTO(92),
    QUEST_REDEEM_RESPONSE(93),
    ENEMYHIT(94),
    GUILDRESULT(95),
    UPDATEACK(96),
    INVDROP(97),
    QUEST_REDEEM(98),
    GOTOACK(99),
    TRADEACCEPTED(100),
    CHANGETRADE(101);

    private final int id;

    MessageType(final int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static MessageType byId(final int id) {
        for (final MessageType type : values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown message type: " + id);
    }
}