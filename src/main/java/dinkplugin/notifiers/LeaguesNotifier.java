package dinkplugin.notifiers;

import dinkplugin.domain.LeagueRelicTier;
import dinkplugin.domain.LeagueTaskDifficulty;
import dinkplugin.message.NotificationBody;
import dinkplugin.message.NotificationType;
import dinkplugin.message.templating.Replacements;
import dinkplugin.message.templating.Template;
import dinkplugin.notifiers.data.LeaguesAreaNotificationData;
import dinkplugin.notifiers.data.LeaguesMasteryNotificationData;
import dinkplugin.notifiers.data.LeaguesRelicNotificationData;
import dinkplugin.notifiers.data.LeaguesTaskNotificationData;
import dinkplugin.util.Utils;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.StructComposition;
import net.runelite.api.WorldType;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import org.jetbrains.annotations.VisibleForTesting;

import javax.inject.Inject;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
public class LeaguesNotifier extends BaseNotifier {
    private static final String AREA_UNLOCK_PREFIX = "Congratulations, you've unlocked a new area: ";
    private static final String RELIC_UNLOCK_PREFIX = "Congratulations, you've unlocked a new Relic: ";
    private static final Pattern TASK_REGEX = Pattern.compile("Congratulations, you've completed an? (?<tier>\\w+) task: (?<task>.+)\\.");
    private static final Pattern MASTERY_REGEX = Pattern.compile("Congratulations, you've unlocked a new .+ Combat Mastery: (?<type>\\w+) (?<tier>\\w+)\\.");

    @VisibleForTesting
    static final int FIRST_AREA_TASKS = 90, SECOND_AREA_TASKS = 200, THIRD_AREA_TASKS = 400;

    /**
     * Value associated with {@link VarbitID#LEAGUE_TYPE} for the current league.
     */
    @VisibleForTesting
    static final int CURRENT_LEAGUE_VERSION = 5;

    /**
     * Short name for the current league.
     */
    @VisibleForTesting
    static final String CURRENT_LEAGUE_NAME = "Raging Echoes";

    /**
     * Trophy name by the required points, in a binary search tree.
     *
     * @see <a href="https://oldschool.runescape.wiki/w/Trailblazer_Reloaded_League#Trophies">Wiki Reference</a>
     * @see <a href="https://github.com/Joshua-F/cs2-scripts/blob/fa31b06ec5a9f6636bf9b9d5cbffbb71df022d06/scripts/%5Bproc%2Cscript731%5D.cs2#L3">CS2 Reference</a>
     */
    private static final NavigableMap<Integer, String> TROPHY_BY_POINTS;

    /**
     * Mapping of the points required to unlock each relic tier.
     */
    private static final NavigableMap<Integer, LeagueRelicTier> TIER_BY_POINTS;

    /**
     * Mapping of each relic name to the tier (1-8).
     *
     * @see <a href="https://oldschool.runescape.wiki/w/Trailblazer_Reloaded_League/Relics">Wiki Reference</a>
     */
    private static final Map<String, LeagueRelicTier> TIER_BY_RELIC;

    /**
     * Mapping of the number of tasks required to unlock an area to the area index (0-3).
     *
     * @see <a href="https://oldschool.runescape.wiki/w/Trailblazer_Reloaded_League/Areas">Wiki reference</a>
     */
    private static final NavigableMap<Integer, Integer> AREA_BY_TASKS;

    private static final Map<String, Integer> ROMAN_NUMERALS;

    @Inject
    private ClientThread clientThread;

    @Override
    public boolean isEnabled() {
        return config.notifyLeagues() &&
            client.getVarbitValue(VarbitID.LEAGUE_TYPE) == CURRENT_LEAGUE_VERSION &&
            client.getWorldType().contains(WorldType.SEASONAL) &&
            accountTracker.hasValidState();
    }

    @Override
    protected String getWebhookUrl() {
        return config.leaguesWebhook();
    }

    public void onGameMessage(String message) {
        if (!isEnabled()) {
            return;
        }
        if (message.startsWith(AREA_UNLOCK_PREFIX)) {
            if (config.leaguesAreaUnlock()) {
                String area = message.substring(AREA_UNLOCK_PREFIX.length(), message.length() - 1);
                notifyAreaUnlock(area);
            }
            return;
        }
        if (message.startsWith(RELIC_UNLOCK_PREFIX)) {
            if (config.leaguesRelicUnlock()) {
                String relic = message.substring(RELIC_UNLOCK_PREFIX.length(), message.length() - 1);
                notifyRelicUnlock(relic);
            }
            return;
        }
        if (config.leaguesTaskCompletion()) {
            Matcher matcher = TASK_REGEX.matcher(message);
            if (matcher.find()) {
                LeagueTaskDifficulty tier = LeagueTaskDifficulty.TIER_BY_LOWER_NAME.get(matcher.group("tier"));
                if (tier != null && tier.ordinal() >= config.leaguesTaskMinTier().ordinal()) {
                    notifyTaskCompletion(tier, matcher.group("task"));
                }
                return;
            }
        }
        if (config.leaguesMasteryUnlock()) {
            var matcher = MASTERY_REGEX.matcher(message);
            if (matcher.find()) {
                String type = matcher.group("type");
                String tier = matcher.group("tier");
                notifyCombatMastery(type, tier);
            }
        }
    }

    private void notifyCombatMastery(String type, String romanTier) {
        Integer tier = ROMAN_NUMERALS.get(romanTier);
        if (tier == null) {
            log.warn("Could not parse combat mastery tier: {}", romanTier);
            return;
        }
        String playerName = Utils.getPlayerName(client);
        Template text = Template.builder()
            .template("%USERNAME% unlocked a new Combat Mastery: %MASTERY%.")
            .replacementBoundary("%")
            .replacement("%USERNAME%", Replacements.ofText(playerName))
            .replacement("%MASTERY%", Replacements.ofWiki(type + " " + romanTier))
            .build();
        createMessage(config.leaguesSendImage(), NotificationBody.builder()
            .type(NotificationType.LEAGUES_MASTERY)
            .text(text)
            .extra(new LeaguesMasteryNotificationData(type, tier))
            .playerName(playerName)
            .seasonalWorld(true)
            .build());
    }

    private void notifyAreaUnlock(String area) {
        Map.Entry<Integer, String> unlocked = numAreasUnlocked();

        int tasksCompleted = client.getVarbitValue(VarbitID.LEAGUE_TOTAL_TASKS_COMPLETED);
        Integer tasksForNextArea = AREA_BY_TASKS.ceilingKey(tasksCompleted + 1);
        Integer tasksUntilNextArea = tasksForNextArea != null ? tasksForNextArea - tasksCompleted : null;

        if (unlocked == null) {
            int i = AREA_BY_TASKS.floorEntry(Math.max(tasksCompleted, 0)).getValue();
            unlocked = Map.entry(i, ith(i));
        }

        String playerName = Utils.getPlayerName(client);
        Template text = Template.builder()
            .template("%USERNAME% selected their %I_TH% region: %AREA%.")
            .replacementBoundary("%")
            .replacement("%USERNAME%", Replacements.ofText(playerName))
            .replacement("%I_TH%", Replacements.ofText(unlocked.getValue()))
            .replacement("%AREA%", Replacements.ofWiki(area, CURRENT_LEAGUE_NAME + " League/Areas/" + area))
            .build();
        createMessage(config.leaguesSendImage(), NotificationBody.builder()
            .type(NotificationType.LEAGUES_AREA)
            .text(text)
            .extra(new LeaguesAreaNotificationData(area, unlocked.getKey(), tasksCompleted, tasksUntilNextArea))
            .playerName(playerName)
            .seasonalWorld(true)
            .build());
    }

    private void notifyRelicUnlock(String relic) {
        int points = client.getVarpValue(VarPlayerID.LEAGUE_POINTS_COMPLETED);
        Integer pointsOfNextTier = TIER_BY_POINTS.ceilingKey(points + 1);
        Integer pointsUntilNextTier = pointsOfNextTier != null ? pointsOfNextTier - points : null;

        LeagueRelicTier relicTier = TIER_BY_RELIC.getOrDefault(relic, LeagueRelicTier.UNKNOWN);
        int tier = relicTier.ordinal();
        int requiredPoints = TIER_BY_POINTS.floorKey(points);

        String playerName = Utils.getPlayerName(client);
        Template text = Template.builder()
            .template("%USERNAME% unlocked a Tier %TIER% Relic: %RELIC%.")
            .replacementBoundary("%")
            .replacement("%USERNAME%", Replacements.ofText(playerName))
            .replacement("%TIER%", Replacements.ofText(String.valueOf(tier)))
            .replacement("%RELIC%", Replacements.ofWiki(relic))
            .build();
        createMessage(config.leaguesSendImage(), NotificationBody.builder()
            .type(NotificationType.LEAGUES_RELIC)
            .text(text)
            .extra(new LeaguesRelicNotificationData(relic, tier, requiredPoints, points, pointsUntilNextTier))
            .playerName(playerName)
            .seasonalWorld(true)
            .build());
    }

    private void notifyTaskCompletion(LeagueTaskDifficulty tier, String task) {
        int taskPoints = tier.getPoints();
        int totalPoints = client.getVarpValue(VarPlayerID.LEAGUE_POINTS_COMPLETED);
        int tasksCompleted = client.getVarbitValue(VarbitID.LEAGUE_TOTAL_TASKS_COMPLETED);
        String playerName = Utils.getPlayerName(client);

        Integer nextAreaTasks = AREA_BY_TASKS.ceilingKey(tasksCompleted + 1);
        Integer tasksUntilNextArea = nextAreaTasks != null ? nextAreaTasks - tasksCompleted : null;

        Map.Entry<Integer, String> trophy = TROPHY_BY_POINTS.floorEntry(totalPoints);
        Integer prevTrophyPoints;
        if (trophy != null) {
            prevTrophyPoints = TROPHY_BY_POINTS.floorKey(totalPoints - taskPoints);
        } else {
            prevTrophyPoints = null;
        }
        boolean newTrophy = trophy != null && (prevTrophyPoints == null || trophy.getKey() > prevTrophyPoints);
        String justEarnedTrophy = newTrophy ? trophy.getValue() : null;
        Integer nextTrophyPoints = TROPHY_BY_POINTS.ceilingKey(totalPoints + 1);
        Integer pointsUntilNextTrophy = nextTrophyPoints != null ? nextTrophyPoints - totalPoints : null;

        Integer nextRelicPoints = TIER_BY_POINTS.ceilingKey(totalPoints + 1);
        Integer pointsUntilNextRelic = nextRelicPoints != null ? nextRelicPoints - totalPoints : null;

        Template text = Template.builder()
            .template(newTrophy
                ? "%USERNAME% completed a %TIER% task, %TASK%, unlocking the %TROPHY% trophy!"
                : "%USERNAME% completed a %TIER% task: %TASK%.")
            .replacementBoundary("%")
            .replacement("%USERNAME%", Replacements.ofText(playerName))
            .replacement("%TIER%", Replacements.ofText(tier.getDisplayName()))
            .replacement("%TASK%", Replacements.ofWiki(task, CURRENT_LEAGUE_NAME + " League/Tasks"))
            .replacement("%TROPHY%", newTrophy
                ? Replacements.ofWiki(trophy.getValue(), String.format("%s %s trophy", CURRENT_LEAGUE_NAME, trophy.getValue().toLowerCase()))
                : Replacements.ofText("?"))
            .build();
        createMessage(config.leaguesSendImage(), NotificationBody.builder()
            .type(NotificationType.LEAGUES_TASK)
            .text(text)
            .extra(new LeaguesTaskNotificationData(task, tier, taskPoints, totalPoints, tasksCompleted, tasksUntilNextArea, pointsUntilNextRelic, pointsUntilNextTrophy, justEarnedTrophy))
            .playerName(playerName)
            .seasonalWorld(true)
            .build());
    }

    /**
     * @return the number of areas that have been unlocked as integer and human name
     */
    private Map.Entry<Integer, String> numAreasUnlocked() {
        // While Jagex's code has 5 areas (2 default, 3 discretionary),
        // most players think just in terms of the 3 discretionary areas,
        // so we disregard Misthalin and consider Karamja as the zeroth area.
        // Thus, the number of unlocked areas is bounded by 3 (instead of 5).
        if (client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_4) > 0) {
            return Map.entry(3, ith(3));
        }
        if (client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_3) > 0) {
            return Map.entry(2, ith(2));
        }
        if (client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_2) > 0) {
            return Map.entry(1, ith(1));
        }
        if (client.getVarbitValue(VarbitID.LEAGUE_AREA_SELECTION_1) > 0) {
            return Map.entry(0, ith(0)); // Karamja
        }
        return null;
    }

    private static String ith(int i) {
        if (i == 0) return "zeroth";
        if (i == 1) return "first";
        if (i == 2) return "second";
        if (i == 3) return "third";
        if (i == 4) return "fourth";
        if (i == 5) return "fifth";
        return String.valueOf(i);
    }

    public void init() {
        clientThread.invokeLater(() -> {
            StructComposition leaguesStruct;
            try {
                leaguesStruct = client.getStructComposition(client.getEnum(2670).getIntVals()[CURRENT_LEAGUE_VERSION - 1]);
            } catch (Exception e) {
                log.warn("Could not find current leagues struct", e);
                return;
            }

            try {
                initTrophies(leaguesStruct);
                log.debug("Trophies: {}", TROPHY_BY_POINTS);
            } catch (Exception e) {
                log.warn("Failed to initialize trophies", e);
            }

            try {
                initRelics(leaguesStruct);
                log.debug("Relics: {}", TIER_BY_RELIC);
                log.debug("Tiers: {}", TIER_BY_POINTS);
            } catch (Exception e) {
                log.warn("Failed to initialize relics", e);
            }
        });
    }

    /**
     * Overwrites {@code TROPHY_BY_POINTS} with thresholds defined in cache
     */
    private void initTrophies(StructComposition leaguesStruct) {
        int[] thresholds = client.getEnum(leaguesStruct.getIntValue(1857)).getIntVals();
        int n = thresholds.length;
        if (n > 0) {
            var names = List.copyOf(TROPHY_BY_POINTS.values());
            TROPHY_BY_POINTS.clear();
            for (int i = 0; i < n; i++) {
                TROPHY_BY_POINTS.put(thresholds[i], names.get(i));
            }
        }
    }

    /**
     * Overwrites {@code TIER_BY_RELIC} with data from cache
     */
    private void initRelics(StructComposition leaguesStruct) {
        int[] tierStructs = client.getEnum(leaguesStruct.getIntValue(870)).getIntVals();
        LeagueRelicTier[] tiers = LeagueRelicTier.values();
        var pointsMap = new TreeMap<Integer, LeagueRelicTier>();
        pointsMap.put(-1, LeagueRelicTier.UNKNOWN);
        for (int tierIndex = 1; tierIndex <= tierStructs.length; tierIndex++) {
            var tier = tierIndex < tiers.length ? tiers[tierIndex] : LeagueRelicTier.UNKNOWN;
            var tierStruct = client.getStructComposition(tierStructs[tierIndex - 1]);
            pointsMap.put(tierStruct.getIntValue(877), tier);

            var relicStructs = client.getEnum(tierStruct.getIntValue(878)).getIntVals();
            for (int relicStruct : relicStructs) {
                var name = client.getStructComposition(relicStruct).getStringValue(879);
                TIER_BY_RELIC.put(name, tier);
            }
        }
        if (pointsMap.size() > 1) {
            TIER_BY_POINTS.clear();
            TIER_BY_POINTS.putAll(pointsMap);
        }
    }

    static {
        ROMAN_NUMERALS = Map.of("I", 1, "II", 2, "III", 3, "IV", 4, "V", 5, "VI", 6);

        AREA_BY_TASKS = Collections.unmodifiableNavigableMap(
            new TreeMap<>(Map.of(0, 0, FIRST_AREA_TASKS, 1, SECOND_AREA_TASKS, 2, THIRD_AREA_TASKS, 3))
        );

        NavigableMap<Integer, String> thresholds = new TreeMap<>();
        thresholds.put(2_000, "Bronze");
        thresholds.put(4_000, "Iron");
        thresholds.put(10_000, "Steel");
        thresholds.put(20_000, "Mithril");
        thresholds.put(30_000, "Adamant");
        thresholds.put(45_000, "Rune");
        thresholds.put(60_000, "Dragon");
        TROPHY_BY_POINTS = thresholds;

        TIER_BY_POINTS = Arrays.stream(LeagueRelicTier.values())
            .collect(Collectors.toMap(LeagueRelicTier::getDefaultPoints, Function.identity(), (a, b) -> null, TreeMap::new));

        TIER_BY_RELIC = new HashMap<>(Map.ofEntries(
            Map.entry("Animal Wrangler", LeagueRelicTier.ONE),
            Map.entry("Lumberjack", LeagueRelicTier.ONE),
            Map.entry("Power Miner", LeagueRelicTier.ONE),
            Map.entry("Corner Cutter", LeagueRelicTier.TWO),
            Map.entry("Dodgy Deals", LeagueRelicTier.TWO),
            Map.entry("Friendly Forager", LeagueRelicTier.TWO),
            Map.entry("Bank Heist", LeagueRelicTier.THREE),
            Map.entry("Clue Compass", LeagueRelicTier.THREE),
            Map.entry("Fairy's Flight", LeagueRelicTier.THREE),
            Map.entry("Equilibrium", LeagueRelicTier.FOUR),
            Map.entry("Golden God", LeagueRelicTier.FOUR),
            Map.entry("Reloaded", LeagueRelicTier.FOUR),
            Map.entry("Production Master", LeagueRelicTier.FIVE),
            Map.entry("Slayer Master", LeagueRelicTier.FIVE),
            Map.entry("Treasure Arbiter", LeagueRelicTier.FIVE),
            Map.entry("Total Recall", LeagueRelicTier.SIX),
            Map.entry("Banker's Note", LeagueRelicTier.SIX),
            Map.entry("Grimoire", LeagueRelicTier.SEVEN),
            Map.entry("Overgrown", LeagueRelicTier.SEVEN),
            Map.entry("Pocket Kingdom", LeagueRelicTier.SEVEN),
            Map.entry("Guardian", LeagueRelicTier.EIGHT),
            Map.entry("Last Stand", LeagueRelicTier.EIGHT),
            Map.entry("Specialist", LeagueRelicTier.EIGHT)
        ));
    }
}
