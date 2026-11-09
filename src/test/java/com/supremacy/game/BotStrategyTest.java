package com.supremacy.game;

import static com.supremacy.game.GameState.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class BotStrategyTest {
  private GameEngine fixture(GameState.Difficulty difficulty) {
    GameEngine g = new GameEngine(new GameEngineTest().board(), 1, difficulty, 42);
    g.state.players.forEach(p -> p.human = false);
    g.state.active = 0;
    g.state.phase = ATTACK;
    Arrays.fill(g.state.owners, 1);
    Arrays.fill(g.state.armies, 10);
    return g;
  }

  @Test
  void exactBattleProbabilitiesRespectDefendingTiesAndMonotonicity() {
    assertEquals(15.0 / 36, BattleOdds.win(1, 1), 1e-12);
    assertEquals(.7542438271604938, BattleOdds.win(2, 1), 1e-12);
    for (int a = 1; a < 40; a++)
      for (int d = 1; d < 40; d++) {
        assertTrue(BattleOdds.win(a + 1, d) >= BattleOdds.win(a, d));
        assertTrue(BattleOdds.win(a, d + 1) <= BattleOdds.win(a, d));
      }
    assertTrue(Double.isFinite(BattleOdds.win(1e12, 1e12)));
    assertEquals(0, BattleOdds.win(0, 1));
    assertEquals(1, BattleOdds.win(1, 0));
  }

  @Test
  void everyLevelDeclinesHopelessAttacks() {
    for (var level : GameState.Difficulty.values()) {
      GameEngine g = fixture(level);
      g.state.owners[0] = 0;
      g.state.armies[0] = 2;
      new BotStrategy(g).step();
      assertEquals(FORTIFY, g.state.phase, level.name());
    }
  }

  @Test
  void strongBotCompletesContinentBeforeTakingPeripheralLand() {
    GameEngine g = fixture(GameState.Difficulty.TeraHard);
    var c = g.board.regions.get(g.index("Brazil")).continent;
    int target = -1, source = -1;
    for (int i = 0; i < g.state.owners.length; i++) {
      if (g.board.regions.get(i).continent == c) g.state.owners[i] = 0;
    }
    for (int i = 0; i < g.state.owners.length; i++) {
      if (g.board.regions.get(i).continent == c) {
        for (int n : g.neighbours(i))
          if (g.board.regions.get(n).continent == c) {
            source = i;
            target = n;
            break;
          }
        if (source >= 0) break;
      }
    }
    g.state.owners[target] = 1;
    Arrays.fill(g.state.armies, 1);
    g.state.armies[source] = 30;
    // All other owned regions cannot attack; source has several possible conquests.
    BotStrategy bot = new BotStrategy(g);
    for (int i = 0; i < 20 && g.state.phase == ATTACK; i++) bot.step();
    assertEquals(OCCUPY, g.state.phase);
    assertEquals(target, g.state.occupationTo);
    for (int i = 0; i < g.state.owners.length; i++)
      if (i != source && i != target) assertEquals(1, g.state.armies[i]);
  }

  @Test
  void reinforcementIsSplitAndObjectiveSurvivesBotRecreation() {
    GameEngine g = fixture(GameState.Difficulty.Hard);
    g.state.phase = REINFORCE;
    g.state.reserve = 20;
    g.state.owners[0] = 0;
    new BotStrategy(g).step();
    assertEquals(15, g.state.reserve);
    String goal = g.state.players.get(0).objective;
    assertFalse(goal.isEmpty());
    new BotStrategy(g).step();
    assertEquals(goal, g.state.players.get(0).objective);
  }
}
