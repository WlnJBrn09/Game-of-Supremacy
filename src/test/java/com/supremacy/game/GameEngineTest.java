package com.supremacy.game;

import static com.supremacy.game.GameState.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

import com.supremacy.board.Board;
import java.io.InputStreamReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GameEngineTest {
  Board board() {
    return new Board(
        new InputStreamReader(
            Objects.requireNonNull(getClass().getResourceAsStream("/board.json"))));
  }

  GameEngine game() {
    return new GameEngine(board(), 1, GameState.Difficulty.Hard, 123456);
  }

  void finishSetup(GameEngine g) {
    for (int p = 0; p < 5; p++) {
      final int player = p;
      int r =
          java.util.stream.IntStream.range(0, g.state.owners.length)
              .filter(i -> g.state.owners[i] == player)
              .findFirst()
              .orElseThrow();
      g.deploy(r, g.state.reserve);
      g.finishDeployment();
    }
  }

  void assign(GameEngine g, String territory, int owner, long armies) {
    int r = g.index(territory);
    g.state.owners[r] = owner;
    g.state.armies[r] = armies;
  }

  @Test
  void fivePlayersRandomDealFiftyFiveEachAndPopulationIncome() {
    GameEngine g = game();
    assertEquals(5, g.state.players.size());
    assertEquals(
        List.of("Player 1", "Charles", "George", "Douglas", "Dan"),
        g.state.players.stream().map(p -> p.name).toList());
    assertEquals(72, Arrays.stream(g.state.owners).count());
    finishSetup(g);
    for (int p = 0; p < 5; p++) assertEquals(55, g.total(p));
    assertEquals(REINFORCE, g.state.phase);
    assertEquals(Math.max(3, g.count(0) / 3) + g.continentIncome(0) + 11, g.state.reserve);
    g.validate();
  }

  @Test
  void tiesDefendAndDiceComparedHighestFirst() {
    assertArrayEquals(new int[] {2, 0}, GameEngine.losses(new int[] {6, 5, 1}, new int[] {6, 5}));
    assertArrayEquals(new int[] {0, 2}, GameEngine.losses(new int[] {6, 5, 1}, new int[] {4, 3}));
    assertArrayEquals(new int[] {1, 1}, GameEngine.losses(new int[] {2, 6, 3}, new int[] {5, 4}));
  }

  @Test
  void conquestOccupationEliminationAndWorldVictory() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    g.state.active = 0;
    for (int i = 0; i < g.state.owners.length; i++) {
      g.state.owners[i] = 0;
      g.state.armies[i] = 1;
    }
    int a = g.index("US-West"), b = g.index("US-Midwest");
    g.state.owners[b] = 1;
    g.state.armies[a] = 100;
    while (g.state.phase == ATTACK) g.attack(a, b, 3);
    assertEquals(OCCUPY, g.state.phase);
    assertEquals(3, g.state.occupationMinimum);
    assertThrows(IllegalArgumentException.class, () -> g.occupy(2));
    g.occupy(99);
    assertEquals(FINISHED, g.state.phase);
    assertEquals(0, g.state.winner);
    g.validate();
  }

  @Test
  void noSecondFortificationOfArrivalsAndOpenBordersTransitOnly() {
    GameEngine g = game();
    g.state.active = 0;
    g.state.phase = FORTIFY;
    assign(g, "US-West", 0, 12);
    assign(g, "US-Midwest", 0, 1);
    assign(g, "US-East", 0, 1);
    int a = g.index("US-West"), b = g.index("US-Midwest"), c = g.index("US-East");
    g.fortify(a, b, 6);
    assertEquals(0, g.movable(b));
    assertThrows(IllegalArgumentException.class, () -> g.fortify(b, c, 1));
    g.fortify(a, c, 3);
    assertEquals(4, g.state.armies[c]);
    // Isolate a simple path through another owner by making all other nodes hostile.
    for (int i = 0; i < g.state.owners.length; i++) g.state.owners[i] = 2;
    assign(g, "Hawaii", 0, 10);
    assign(g, "Japan", 1, 2);
    assign(g, "Korea", 0, 2);
    int h = g.index("Hawaii"), k = g.index("Korea");
    assertFalse(g.connected(h, k, 0));
    g.state.borders[0][1] = g.state.borders[1][0] = true;
    assertTrue(g.connected(h, k, 0));
    assertFalse(g.canAttack(h, k));
  }

  @Test
  void cardsEscalateAndOwnedMatchBonus() {
    GameEngine g = game();
    g.state.phase = REINFORCE;
    g.state.reserve = 0;
    int[] set = {0, 3, 6};
    for (int c : set) {
      g.state.deck.remove(Integer.valueOf(c));
      g.state.players.get(0).cards.add(c);
    }
    g.state.owners[0] = 0;
    long before = g.state.armies[0];
    g.tradeCards(List.of(0, 3, 6));
    assertEquals(4, g.state.reserve);
    assertEquals(before + 2, g.state.armies[0]);
    assertEquals(6, g.nextSetValue());
    g.state.tradeCount = 5;
    assertEquals(15, g.nextSetValue());
    g.state.tradeCount = 6;
    assertEquals(20, g.nextSetValue());
    assertTrue(g.validSet(List.of(0, 1, g.state.owners.length)));
    assertEquals(3, g.symbol(g.state.owners.length));
    assertFalse(g.validSet(List.of(0, 3, 1)));
    g.validate();
  }

  GameState.Offer offer(GameEngine g) {
    GameState.Offer o = new GameState.Offer();
    o.from = 0;
    o.to = 1;
    o.openBorders = true;
    return o;
  }

  @Test
  void pendingOffersDoNotReserveAssetsAndAcceptanceIsImmediate() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    assign(g, "US-West", 0, 15);
    assign(g, "US-Midwest", 1, 3);
    GameState.Offer o = offer(g);
    o.giveArmies = 4;
    o.giveSource = g.index("US-West");
    o.giveTarget = g.index("US-Midwest");
    o = g.sendOffer(o);
    assertEquals(15, g.state.armies[o.giveSource]);
    g.state.active = 1;
    assertTrue(g.answer(o.id, true));
    assertEquals(11, g.state.armies[o.giveSource]);
    assertEquals(7, g.state.armies[o.giveTarget]);
    assertTrue(g.state.borders[0][1]);
    assertTrue(g.responses().isEmpty());
    g.state.active = 0;
    assertEquals(1, g.responses().size());
    g.validate();
  }

  @Test
  void staleOfferInvalidatesWithoutChangingArmies() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    assign(g, "US-West", 0, 15);
    assign(g, "US-Midwest", 1, 3);
    GameState.Offer o = offer(g);
    o.giveArmies = 10;
    o.giveSource = g.index("US-West");
    o.giveTarget = g.index("US-Midwest");
    o = g.sendOffer(o);
    g.state.armies[o.giveSource] = 5;
    long[] before = g.state.armies.clone();
    g.state.active = 1;
    assertFalse(g.answer(o.id, true));
    assertEquals(GameState.Offer.Status.INVALID, o.status);
    assertArrayEquals(before, g.state.armies);
  }

  @Test
  void counterofferWaitsForOriginalSendersTurn() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    GameState.Offer old = g.sendOffer(offer(g));
    g.state.active = 1;
    GameState.Offer replacement = new GameState.Offer();
    replacement.from = 1;
    replacement.to = 0;
    replacement.openBorders = true;
    GameState.Offer next = g.counter(old.id, replacement);
    assertEquals(GameState.Offer.Status.COUNTERED, old.status);
    assertTrue(g.inbox().isEmpty());
    g.state.active = 0;
    assertEquals(next, g.inbox().get(0));
    assertTrue(g.answer(next.id, true));
  }

  @Test
  void territoryTradeReturnsGarrisonsAndConservesArmies() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    assign(g, "US-West", 0, 10);
    assign(g, "US-Midwest", 0, 3);
    assign(g, "US-East", 1, 10);
    assign(g, "Alaska", 1, 5);
    long total = Arrays.stream(g.state.armies).sum();
    GameState.Offer o = offer(g);
    o.giveTerritories.add(g.index("US-West"));
    o.wantTerritories.add(g.index("Alaska"));
    o = g.sendOffer(o);
    g.state.active = 1;
    assertTrue(g.answer(o.id, true));
    assertEquals(1, g.state.owners[g.index("US-West")]);
    assertEquals(0, g.state.owners[g.index("Alaska")]);
    assertEquals(1, g.state.armies[g.index("US-West")]);
    assertEquals(total, Arrays.stream(g.state.armies).sum());
    g.validate();
  }

  @Test
  void autosaveRestoresPendingOccupationRandomSequenceAndRecoversBackup(@TempDir Path dir)
      throws Exception {
    GameEngine g = game();
    finishSetup(g);
    SaveStore store = new SaveStore(dir.resolve("auto.json"));
    store.save(g);
    g.deploy(
        java.util.stream.IntStream.range(0, g.state.owners.length)
            .filter(i -> g.state.owners[i] == g.state.active)
            .findFirst()
            .orElseThrow(),
        g.state.reserve);
    store.save(g);
    GameEngine loaded = store.load(g.board);
    assertArrayEquals(g.state.armies, loaded.state.armies);
    assertEquals(g.state.rng, loaded.state.rng);
    assertEquals(g.random(100), loaded.random(100));
    Files.writeString(dir.resolve("auto.json"), "broken");
    GameEngine backup = store.load(g.board);
    assertEquals(REINFORCE, backup.state.phase);
    assertFalse(store.warning().isEmpty());
  }

  @Test
  void tradeDoesNotSpendUnaffectedFortificationArmies() {
    GameEngine g = game();
    g.state.phase = FORTIFY;
    g.state.active = 0;
    assign(g, "US-West", 0, 20);
    assign(g, "US-Midwest", 1, 10);
    assign(g, "Ontario", 1, 10);
    GameState.Offer o = offer(g);
    o.giveArmies = 4;
    o.giveSource = g.index("US-West");
    o.giveTarget = g.index("US-Midwest");
    o = g.sendOffer(o);
    g.state.active = 1;
    assertTrue(g.answer(o.id, true));
    assertEquals(9, g.movable(g.index("US-Midwest")));
    assertEquals(9, g.movable(g.index("Ontario")));
  }

  @Test
  void pendingOccupationIsDurableAndDiceCannotBeRerolled(@TempDir Path dir) {
    GameEngine g = game();
    g.state.phase = ATTACK;
    assign(g, "US-West", 0, 100);
    assign(g, "US-Midwest", 1, 1);
    int a = g.index("US-West"), b = g.index("US-Midwest");
    while (g.state.phase == ATTACK) g.attack(a, b, 3);
    SaveStore store = new SaveStore(dir.resolve("match.json"));
    store.save(g);
    GameEngine copy = store.load(g.board);
    assertEquals(OCCUPY, copy.state.phase);
    assertEquals(0, copy.state.armies[b]);
    assertArrayEquals(g.state.attackDice, copy.state.attackDice);
    assertEquals(g.state.rng, copy.state.rng);
    assertThrows(IllegalArgumentException.class, () -> copy.attack(a, b, 3));
    copy.occupy(3);
    copy.validate();
  }

  @Test
  void eliminationTransfersOnlyCardsAndAwardsOneConquestCard() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    for (int r = 0; r < g.state.owners.length; r++) {
      g.state.owners[r] = 2;
      g.state.armies[r] = 1;
    }
    assign(g, "US-West", 0, 100);
    assign(g, "US-Midwest", 1, 1);
    g.state.deck.remove(Integer.valueOf(4));
    g.state.players.get(1).cards.add(4);
    long before = g.total(0);
    while (g.state.phase == ATTACK) g.attack(g.index("US-West"), g.index("US-Midwest"), 3);
    assertEquals(List.of(4), g.state.players.get(0).cards);
    assertTrue(g.state.players.get(1).cards.isEmpty());
    g.occupy(3);
    assertTrue(g.total(0) <= before);
    g.finishAttacks();
    g.endTurn();
    assertEquals(2, g.state.players.get(0).cards.size());
    g.validate();
  }

  @Test
  void territoryExchangeNeedsTheRecipientsOwnGarrison() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    for (int r = 0; r < g.state.owners.length; r++) {
      g.state.owners[r] = 1;
      g.state.armies[r] = 1;
    }
    assign(g, "US-West", 0, 10);
    assign(g, "Alaska", 0, 1);
    GameState.Offer o = offer(g);
    o.giveTerritories.add(g.index("US-West"));
    assertFalse(g.offerValid(o));
    assertThrows(IllegalArgumentException.class, () -> g.sendOffer(o));
  }

  @Test
  void eliminationCardTradeInterruptsAttacksUntilDeployment() {
    GameEngine g = game();
    g.state.phase = ATTACK;
    for (int r = 0; r < g.state.owners.length; r++) {
      g.state.owners[r] = 2;
      g.state.armies[r] = 1;
    }
    assign(g, "US-West", 0, 100);
    assign(g, "US-Midwest", 1, 1);
    for (int c : List.of(0, 3, 6)) {
      g.state.deck.remove(Integer.valueOf(c));
      g.state.players.get(0).cards.add(c);
    }
    for (int c : List.of(1, 4, 7)) {
      g.state.deck.remove(Integer.valueOf(c));
      g.state.players.get(1).cards.add(c);
    }
    while (g.state.phase == ATTACK) g.attack(g.index("US-West"), g.index("US-Midwest"), 3);
    g.occupy(3);
    assertEquals(REINFORCE, g.state.phase);
    assertEquals(0, g.state.reserve);
    assertThrows(IllegalArgumentException.class, g::finishDeployment);
    g.tradeCards(g.findSet(0));
    assertEquals(4, g.state.reserve);
    g.deploy(g.index("US-West"), 4);
    g.finishDeployment();
    assertEquals(ATTACK, g.state.phase);
    g.validate();
  }

  @Test
  void seededCompleteBotMatchesReachWorldConquest() {
    for (GameState.Difficulty difficulty : Arrays.asList(GameState.Difficulty.values())) {
      for (long seed : new long[] {721, 42, 2026}) {
        GameEngine g = new GameEngine(board(), 1, difficulty, seed);
        for (var p : g.state.players) p.human = false;
        BotStrategy bots = new BotStrategy(g);
        for (int actions = 0; actions < 50000 && g.state.phase != FINISHED; actions++) {
          bots.step();
          if (actions % 100 == 0) g.validate();
        }
        assertEquals(
            FINISHED,
            g.state.phase,
            "Unfinished " + difficulty + " seed " + seed + " turn " + g.state.turn);
        g.validate();
      }
    }
  }

  @Test
  void conquestChanceReflectsBothStacksWithoutUsingRandomness() {
    GameEngine g = game();
    finishSetup(g);
    assign(g, "Brazil", 0, 2);
    assign(g, "Peru", 1, 5);
    long rng = g.state.rng;
    double weak = g.conquestChance(g.index("Brazil"), g.index("Peru"));
    g.state.armies[g.index("Brazil")] = 30;
    double strong = g.conquestChance(g.index("Brazil"), g.index("Peru"));
    assertTrue(weak < .05 && strong > .95 && strong <= 1, weak + " " + strong);
    assertEquals(rng, g.state.rng);
  }
}
