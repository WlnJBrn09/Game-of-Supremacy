package com.supremacy.game;

import static com.supremacy.game.GameState.Phase.*;

import java.util.*;

/** Fair-information, bounded strategic planning. A continent objective persists across turns. */
public final class BotStrategy {
  private final GameEngine game;
  private int plannedTurn = -1, actions;
  private int searchBudget;
  private static final int[] DEPTH = {0, 1, 2, 3, 5, 7};
  private static final int[] BUDGET = {0, 20, 80, 240, 700, 1800};
  private static final double[] CONFIDENCE = {.35, .50, .66, .73, .78, .80};

  public BotStrategy(GameEngine game) {
    this.game = game;
  }

  public void step() {
    GameState s = game.state;
    if (s.phase == FINISHED || s.players.get(s.active).human) return;
    if (plannedTurn != s.turn) {
      plannedTurn = s.turn;
      actions = 0;
      chooseObjective();
    }
    if (s.phase != SETUP && s.phase != OCCUPY) {
      List<GameState.Offer> inbox = game.inbox();
      if (!inbox.isEmpty()) {
        GameState.Offer o = inbox.get(0);
        if (!game.offerValid(o)) {
          game.answer(o.id, true);
          return;
        }
        double value = offerValue(o);
        if (value >= 0) {
          game.answer(o.id, true);
          return;
        }
        if (value > -12
            && game.state.offers.stream()
                    .filter(x -> x.from == s.active && x.status == GameState.Offer.Status.PENDING)
                    .count()
                < 3) {
          GameState.Offer counter = new GameState.Offer();
          counter.from = o.to;
          counter.to = o.from;
          counter.giveTerritories.addAll(o.wantTerritories);
          counter.wantTerritories.addAll(o.giveTerritories);
          counter.giveArmies = o.wantArmies;
          counter.giveSource = o.wantSource;
          counter.giveTarget = o.wantTarget;
          counter.wantArmies = o.giveArmies;
          counter.wantSource = o.giveSource;
          counter.wantTarget = o.giveTarget;
          counter.openBorders = true;
          if (game.offerValid(counter)) {
            game.counter(o.id, counter);
            return;
          }
        }
        game.answer(o.id, false);
        return;
      }
    }
    switch (s.phase) {
      case SETUP, REINFORCE -> {
        if (s.phase == REINFORCE) {
          List<Integer> set = game.findSet(s.active);
          if (!set.isEmpty()) {
            game.tradeCards(set);
            return;
          }
        }
        if (s.reserve > 0) {
          int target = bestDeployment();
          // Allocate in batches so threatened bonus borders and campaign fronts both get troops.
          long batch = s.difficulty.ordinal() < 2 ? s.reserve : Math.max(1, s.reserve / 4);
          game.deploy(target, batch);
        } else game.finishDeployment();
      }
      case ATTACK -> {
        if (actions++ == 0 && s.difficulty.ordinal() >= 2) proposeBorders();
        if (actions > 300) {
          game.finishAttacks();
          return;
        }
        int[] attack = bestAttack();
        if (attack == null) game.finishAttacks();
        else game.attack(attack[0], attack[1], (int) Math.min(3, s.armies[attack[0]] - 1));
      }
      case OCCUPY -> {
        int from = s.occupationFrom;
        long leave =
            Math.max(1, Math.min(s.armies[from] - s.occupationMinimum, defenceReserve(from)));
        game.occupy(Math.max(s.occupationMinimum, s.armies[from] - leave));
      }
      case FORTIFY -> {
        int from = -1, to = -1;
        double best = 0;
        for (int a = 0; a < s.owners.length; a++)
          if (s.owners[a] == s.active && game.movable(a) > 0 && s.armies[a] > defenceReserve(a)) {
            for (int b = 0; b < s.owners.length; b++)
              if (a != b && s.owners[b] == s.active && game.connected(a, b, s.active)) {
                double score = frontValue(b) - frontValue(a);
                if (score > best) {
                  best = score;
                  from = a;
                  to = b;
                }
              }
          }
        if (from < 0 || actions++ > 330) {
          game.endTurn();
          return;
        }
        long amount =
            Math.min(game.movable(from), Math.max(0, s.armies[from] - defenceReserve(from)));
        if (amount > 0) game.fortify(from, to, amount);
        else game.endTurn();
      }
      default -> {}
    }
  }

  private void chooseObjective() {
    GameState s = game.state;
    if (s.phase == FINISHED) return;
    String old = s.players.get(s.active).objective;
    double best = -Double.MAX_VALUE;
    String chosen = old;
    for (var c : game.board.continents) {
      int total = 0, owned = 0;
      long resistance = 0;
      for (int i = 0; i < s.owners.length; i++)
        if (game.board.regions.get(i).continent == c) {
          total++;
          if (s.owners[i] == s.active) {
            owned++;
          } else resistance += s.armies[i];
        }
      if (owned == total) continue;
      // Prefer a reachable, affordable campaign; retain a viable goal across turns.
      int access = 0, perimeter = 0;
      double force = 0;
      for (int i = 0; i < s.owners.length; i++) {
        if (s.owners[i] == s.active) {
          boolean reaches = game.board.regions.get(i).continent == c;
          for (int n : game.neighbours(i))
            if (game.board.regions.get(n).continent == c) reaches = true;
          if (reaches) {
            access++;
            force += Math.max(0, s.armies[i] - 1);
          }
        }
        if (game.board.regions.get(i).continent == c) {
          for (int n : game.neighbours(i))
            if (game.board.regions.get(n).continent != c) {
              perimeter++;
              break;
            }
        }
      }
      if (access == 0) continue;
      double cost = resistance * .9 + total - owned;
      double score =
          owned * 8.0 / total
              + GameEngine.BONUSES.get(c.name()) * 2.0 / (1 + perimeter * .3)
              - cost / Math.max(4, force + s.reserve) * 3
              + (c.name().equals(old) ? 1.8 : 0);
      if (score > best) {
        best = score;
        chosen = c.name();
      }
    }
    s.players.get(s.active).objective = chosen;
  }

  private boolean ownsContinent(int player, int region) {
    var c = game.board.regions.get(region).continent;
    for (int i = 0; i < game.state.owners.length; i++)
      if (game.board.regions.get(i).continent == c && game.state.owners[i] != player) return false;
    return true;
  }

  private long defenceReserve(int r) {
    long strongest = 0;
    for (int n : game.neighbours(r))
      if (game.state.owners[n] != game.state.active)
        strongest = Math.max(strongest, game.state.armies[n] - 1);
    if (strongest == 0) return 1;
    double fraction = ownsContinent(game.state.active, r) ? .85 : .35;
    return Math.max(1, (long) Math.ceil(strongest * fraction));
  }

  private double frontValue(int r) {
    GameState s = game.state;
    if (enemyPressure(r) == 0) return -10 - s.armies[r] * .01;
    double deficit = defenceReserve(r) - s.armies[r];
    return Math.max(-8, deficit) * (ownsContinent(s.active, r) ? 2 : .5)
        - Math.log1p(s.armies[r])
        + (game.board.regions.get(r).continent.name().equals(s.players.get(s.active).objective)
            ? 4
            : 0);
  }

  private int bestDeployment() {
    GameState s = game.state;
    int best = -1;
    double value = -Double.MAX_VALUE;
    for (int r = 0; r < s.owners.length; r++)
      if (s.owners[r] == s.active) {
        double score = frontValue(r);
        for (int n : game.neighbours(r))
          if (s.owners[n] != s.active
              && game.board
                  .regions
                  .get(n)
                  .continent
                  .name()
                  .equals(s.players.get(s.active).objective)) score += 8;
        if (s.difficulty.ordinal() < 2) score += game.random(20);
        if (score > value) {
          value = score;
          best = r;
        }
      }
    return best;
  }

  private int[] bestAttack() {
    GameState s = game.state;
    int level = s.difficulty.ordinal();
    double best = 0;
    int[] result = null;
    for (int a = 0; a < s.owners.length; a++)
      if (s.owners[a] == s.active && s.armies[a] > 1)
        for (int b : game.neighbours(a)) {
          if (!game.canAttack(a, b)) continue;
          double force = s.armies[a] - 1.0;
          double odds = BattleOdds.win(force, s.armies[b]);
          if (odds < CONFIDENCE[level]) continue;
          boolean[] captured = new boolean[s.owners.length];
          captured[b] = true;
          double remaining = Math.max(0, force - s.armies[b] * .9 - 1);
          double reward = captureValue(b, captured) + (!s.conquered ? 3 : 0);
          searchBudget = BUDGET[level];
          reward += campaign(b, remaining, DEPTH[level], captured) * .5;
          double exposure = 0;
          if (level >= 2) {
            // The campaign must also survive the other four players' next turns.
            double retaliation = 0;
            for (int n : game.neighbours(b))
              if (s.owners[n] != s.active && !captured[n])
                retaliation = Math.max(retaliation, s.armies[n] - 1.0);
            exposure = Math.max(0, retaliation - remaining) / Math.max(1, force) * 4;
            if (ownsContinent(s.active, a))
              exposure +=
                  Math.max(0, defenceReserve(a) - (s.armies[a] - force)) / Math.max(1, force) * 4;
          }
          double score =
              odds * reward
                  - (1 - odds) * Math.min(force, 30)
                  - s.armies[b] / Math.max(1, force) * 3
                  - exposure;
          if (level < 2) score += (game.random(101) / 100.0 - .5) * (3 - level);
          if (score > best) {
            best = score;
            result = new int[] {a, b};
          }
        }
    return result;
  }

  private double captureValue(int r, boolean[] captured) {
    GameState s = game.state;
    var continent = game.board.regions.get(r).continent;
    int missing = 0, enemyRemaining = 0;
    boolean enemyBonus = ownsContinent(s.owners[r], r);
    for (int i = 0; i < s.owners.length; i++)
      if (i != r && captured[i] && game.board.regions.get(i).continent == continent)
        enemyBonus = false;
    for (int i = 0; i < s.owners.length; i++) {
      if (!captured[i]
          && s.owners[i] != s.active
          && game.board.regions.get(i).continent == continent) missing++;
      if (!captured[i] && s.owners[i] == s.owners[r]) enemyRemaining++;
    }
    double value = 2;
    if (continent.name().equals(s.players.get(s.active).objective)) value += 3;
    if (missing == 0) value += GameEngine.BONUSES.get(continent.name()) * 8;
    if (enemyBonus) value += GameEngine.BONUSES.get(continent.name()) * 2;
    if (enemyRemaining == 0) value += 12; // Never inspect another player's private cards.
    return value;
  }

  private double campaign(int r, double force, int depth, boolean[] captured) {
    if (depth == 0 || force < 2 || searchBudget-- <= 0) return 0;
    double best = 0;
    for (int n : game.neighbours(r)) {
      if (captured[n] || game.state.owners[n] == game.state.active) continue;
      double odds = BattleOdds.win(force, game.state.armies[n]);
      if (odds < CONFIDENCE[game.state.difficulty.ordinal()]) continue;
      captured[n] = true;
      double remaining = force - game.state.armies[n] * .9 - 1;
      double value =
          odds * (captureValue(n, captured) + .55 * campaign(n, remaining, depth - 1, captured))
              - game.state.armies[n] / Math.max(1, force) * 4
              - (1 - odds) * Math.min(force, 30);
      captured[n] = false;
      best = Math.max(best, value);
      if (searchBudget <= 0) break;
    }
    return best;
  }

  private long enemyPressure(int r) {
    long n = 0;
    for (int next : game.neighbours(r))
      if (game.state.owners[next] != game.state.active)
        n = Math.addExact(n, game.state.armies[next]);
    return n;
  }

  private double offerValue(GameState.Offer o) {
    double value = o.giveArmies - o.wantArmies;
    for (int r : o.giveTerritories) value += territoryValue(r);
    for (int r : o.wantTerritories) value -= territoryValue(r);
    if (o.openBorders) value += 2;
    return value;
  }

  private double territoryValue(int r) {
    return (ownsContinent(game.state.active, r)
            ? 6 + GameEngine.BONUSES.get(game.board.regions.get(r).continent.name()) * 4
            : 6)
        + (game.board
                .regions
                .get(r)
                .continent
                .name()
                .equals(game.state.players.get(game.state.active).objective)
            ? 5
            : 0);
  }

  private void proposeBorders() {
    GameState s = game.state;
    if (s.turn < 5
        || s.offers.stream()
            .anyMatch(o -> o.from == s.active && o.status == GameState.Offer.Status.PENDING))
      return;
    int strongest = 0;
    for (int p = 1; p < 5; p++) if (game.total(p) > game.total(strongest)) strongest = p;
    for (int p = 0; p < 5; p++)
      if (p != s.active && p != strongest && game.alive(p) && !s.borders[s.active][p]) {
        boolean bordering = false;
        for (int r = 0; r < s.owners.length; r++)
          if (s.owners[r] == s.active)
            for (int n : game.neighbours(r)) if (s.owners[n] == p) bordering = true;
        if (bordering) {
          GameState.Offer o = new GameState.Offer();
          o.from = s.active;
          o.to = p;
          o.openBorders = true;
          // Swap peripheral land for land in each player's persistent objective.
          String ours = s.players.get(s.active).objective, theirs = s.players.get(p).objective;
          outer:
          for (int a = 0; a < s.owners.length; a++)
            if (s.owners[a] == s.active
                && !game.board.regions.get(a).continent.name().equals(ours)
                && game.board.regions.get(a).continent.name().equals(theirs)) {
              boolean complete = true;
              for (int r = 0; r < s.owners.length; r++)
                if (game.board.regions.get(r).continent == game.board.regions.get(a).continent
                    && s.owners[r] != s.active) complete = false;
              if (complete) continue;
              for (int b = 0; b < s.owners.length; b++)
                if (s.owners[b] == p
                    && game.board.regions.get(b).continent.name().equals(ours)
                    && !game.board.regions.get(b).continent.name().equals(theirs)) {
                  o.giveTerritories.add(a);
                  o.wantTerritories.add(b);
                  if (game.offerValid(o)) break outer;
                  o.giveTerritories.clear();
                  o.wantTerritories.clear();
                }
            }
          // A small adjacent gift may strengthen a buffer against the leading opponent.
          if (o.giveTerritories.isEmpty() && strongest != s.active)
            armyGift:
            for (int a = 0; a < s.owners.length; a++)
              if (s.owners[a] == s.active && s.armies[a] > 12)
                for (int b : game.neighbours(a))
                  if (s.owners[b] == p) {
                    for (int n : game.neighbours(b))
                      if (s.owners[n] == strongest && s.armies[n] > s.armies[b]) {
                        o.giveSource = a;
                        o.giveTarget = b;
                        o.giveArmies = Math.min(3, (s.armies[a] - 1) / 5);
                        break armyGift;
                      }
                  }
          game.sendOffer(o);
          return;
        }
      }
  }
}
