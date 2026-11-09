package com.supremacy.game;

import static com.supremacy.game.GameState.Phase.*;

import java.util.*;

/** Fair-information, bounded strategic planning. A continent objective persists across turns. */
public final class BotStrategy {
  private final GameEngine game;
  private int plannedTurn = -1, actions;

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
          game.deploy(target, s.reserve);
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
            Math.max(1, Math.min(s.armies[from] - s.occupationMinimum, enemyPressure(from) / 2));
        game.occupy(Math.max(s.occupationMinimum, s.armies[from] - leave));
      }
      case FORTIFY -> {
        int from = -1, to = -1;
        double best = 0;
        for (int a = 0; a < s.owners.length; a++)
          if (s.owners[a] == s.active && game.movable(a) > 0) {
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
        long amount = game.movable(from);
        if (enemyPressure(from) > 0) amount = Math.max(0, amount - enemyPressure(from) / 2);
        if (amount > 0) game.fortify(from, to, amount);
        else game.endTurn();
      }
      default -> {}
    }
  }

  private void chooseObjective() {
    GameState s = game.state;
    if (s.phase == SETUP || s.phase == FINISHED) return;
    String old = s.players.get(s.active).objective;
    double best = -Double.MAX_VALUE;
    String chosen = old;
    for (var c : game.board.continents) {
      int total = 0, owned = 0;
      long resistance = 0;
      int borders = 0;
      for (int i = 0; i < s.owners.length; i++)
        if (game.board.regions.get(i).continent == c) {
          total++;
          if (s.owners[i] == s.active) {
            owned++;
            for (int n : game.neighbours(i)) if (s.owners[n] != s.active) borders++;
          } else resistance += s.armies[i];
        }
      double score =
          owned * 7.0 / Math.max(1, total)
              + GameEngine.BONUSES.get(c.name()) * .55
              - Math.log1p(resistance) * .45
              - borders * .08
              + (c.name().equals(old) ? 1.25 : 0);
      if (owned == total) score -= 6;
      if (score > best) {
        best = score;
        chosen = c.name();
      }
    }
    s.players.get(s.active).objective = chosen;
  }

  private double frontValue(int r) {
    GameState s = game.state;
    double threat = enemyPressure(r);
    if (threat == 0) return -10;
    return threat * .6
        - s.armies[r] * .45
        + (game.board.regions.get(r).continent.name().equals(s.players.get(s.active).objective)
            ? 7
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
    double best = level < 2 ? -8 : 0;
    int[] result = null;
    for (int a = 0; a < s.owners.length; a++)
      if (s.owners[a] == s.active && s.armies[a] > 1)
        for (int b : game.neighbours(a))
          if (game.canAttack(a, b)) {
            double ratio = (s.armies[a] - 1.0) / s.armies[b];
            double threshold = new double[] {.65, .85, 1.05, 1.15, 1.2, 1.25}[level];
            if (ratio < threshold) continue;
            double score = Math.log1p(ratio) * 3 - 2;
            String goal = s.players.get(s.active).objective;
            if (game.board.regions.get(b).continent.name().equals(goal)) score += 4;
            int remaining = 0;
            boolean ownedEnemyContinent = true;
            var c = game.board.regions.get(b).continent;
            for (int r = 0; r < s.owners.length; r++)
              if (game.board.regions.get(r).continent == c) {
                if (s.owners[r] != s.active) remaining++;
                if (s.owners[r] != s.owners[b]) ownedEnemyContinent = false;
              }
            if (remaining == 1) score += GameEngine.BONUSES.get(c.name()) * 2;
            if (ownedEnemyContinent) score += GameEngine.BONUSES.get(c.name());
            if (game.count(s.owners[b]) == 1) score += 12;
            if (level >= 2) {
              boolean[] seen = new boolean[s.owners.length];
              score +=
                  lookAhead(
                          b,
                          Math.max(0, s.armies[a] - 1 - s.armies[b] * 1.1),
                          Math.min(4, level - 1),
                          seen)
                      * .5;
              double exposed = Math.max(0, enemyPressure(a) - (s.armies[a] - 1));
              score -= Math.log1p(exposed) * .7;
            }
            if (level < 3) score += (game.random(101) / 100.0 - .5) * (4 - level);
            if (score > best) {
              best = score;
              result = new int[] {a, b};
            }
          }
    return result;
  }

  private double lookAhead(int r, double force, int depth, boolean[] seen) {
    if (depth == 0 || force < 2) return 0;
    seen[r] = true;
    double best = 0;
    for (int n : game.neighbours(r))
      if (!seen[n] && game.state.owners[n] != game.state.active) {
        double left = force - game.state.armies[n] * 1.1;
        if (left > 1) {
          double score =
              1
                  + (game.board
                          .regions
                          .get(n)
                          .continent
                          .name()
                          .equals(game.state.players.get(game.state.active).objective)
                      ? 2
                      : 0)
                  + lookAhead(n, left, depth - 1, seen);
          best = Math.max(best, score);
        }
      }
    seen[r] = false;
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
    return 6
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
