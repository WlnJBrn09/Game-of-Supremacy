package com.supremacy.game;

import static com.supremacy.game.GameState.Phase.*;

import com.supremacy.board.Board;
import java.util.*;

/** All rules live here; the UI and bots use exactly the same validated commands. */
public final class GameEngine {
  public static final Map<String, Integer> BONUSES =
      Map.of(
          "North America",
          9,
          "South America",
          3,
          "Europe",
          6,
          "Africa",
          5,
          "Asia",
          8,
          "Australia",
          4,
          "Middle-East",
          3);
  public final Board board;
  public final GameState state;
  private final int[][] adjacent;
  private Runnable listener = () -> {};

  public GameEngine(Board board, int humans, GameState.Difficulty difficulty, long seed) {
    check(humans >= 1 && humans <= 5, "Choose one to five humans");
    this.board = board;
    state = new GameState();
    state.difficulty = difficulty;
    state.rng = seed == 0 ? 1 : seed;
    int n = board.regions.size();
    state.owners = new int[n];
    state.armies = new long[n];
    state.moved = new long[n];
    for (Board.Region r : board.regions) state.territoryIds.add(r.id);
    for (int i = 0; i < 5; i++) {
      GameState.Player p = new GameState.Player();
      p.human = i < humans;
      p.name = p.human ? "Player " + (i + 1) : GameState.BOT_NAMES[i - humans];
      state.players.add(p);
    }
    adjacent = adjacency();
    List<Integer> deal = new ArrayList<>();
    for (int i = 0; i < n; i++) deal.add(i);
    shuffle(deal);
    for (int i = 0; i < n; i++) {
      state.owners[deal.get(i)] = i % 5;
      state.armies[deal.get(i)] = 1;
    }
    for (int i = 0; i < n + 2; i++) state.deck.add(i);
    shuffle(state.deck);
    state.reserve = 55 - count(0);
    log("Random territories assigned. Each player starts with 55 armies.");
  }

  public GameEngine(Board board, GameState restored) {
    this.board = board;
    this.state = restored;
    adjacent = adjacency();
    validate();
  }

  private int[][] adjacency() {
    int[][] a = new int[board.regions.size()][];
    for (int i = 0; i < a.length; i++) {
      Board.Region r = board.regions.get(i);
      a[i] = board.links(r).stream().mapToInt(e -> board.regions.indexOf(e.other(r))).toArray();
    }
    return a;
  }

  public void onChange(Runnable listener) {
    this.listener = Objects.requireNonNull(listener);
  }

  private void changed() {
    listener.run();
  }

  public int[] neighbours(int r) {
    region(r);
    return adjacent[r].clone();
  }

  public String name(int r) {
    return board.regions.get(r).name;
  }

  public int count(int player) {
    int n = 0;
    for (int owner : state.owners) if (owner == player) n++;
    return n;
  }

  public long total(int player) {
    long n = 0;
    for (int i = 0; i < state.armies.length; i++)
      if (state.owners[i] == player) n = Math.addExact(n, state.armies[i]);
    return n;
  }

  public boolean alive(int player) {
    return count(player) > 0;
  }

  public int index(String name) {
    return board.regions.indexOf(board.region(name));
  }

  public long continentIncome(int player) {
    long n = 0;
    for (Board.Continent c : board.continents) {
      boolean all = true;
      for (int i = 0; i < state.owners.length; i++)
        if (board.regions.get(i).continent == c && state.owners[i] != player) {
          all = false;
          break;
        }
      if (all) n += BONUSES.get(c.name());
    }
    return n;
  }

  public long income(int player) {
    return Math.addExact(
        Math.max(3, count(player) / 3) + continentIncome(player), total(player) / 5);
  }

  public boolean adjacent(int a, int b) {
    region(a);
    region(b);
    return Arrays.stream(adjacent[a]).anyMatch(i -> i == b);
  }

  public long movable(int r) {
    region(r);
    return Math.max(0, state.armies[r] - state.moved[r] - 1);
  }

  public boolean canAttack(int from, int to) {
    return state.phase == ATTACK
        && validRegion(from)
        && validRegion(to)
        && state.owners[from] == state.active
        && state.owners[to] != state.active
        && state.armies[from] > 1
        && adjacent(from, to);
  }

  public void deploy(int r, long amount) {
    check(state.phase == SETUP || state.phase == REINFORCE, "Not a deployment phase");
    own(r);
    check(amount > 0 && amount <= state.reserve, "Invalid reinforcement amount");
    long next = Math.addExact(state.armies[r], amount);
    state.armies[r] = next;
    state.reserve -= amount;
    changed();
  }

  public void finishDeployment() {
    check(
        (state.phase == SETUP || state.phase == REINFORCE) && state.reserve == 0,
        "Place all remaining armies first");
    if (state.phase == SETUP) {
      if (state.active < 4) {
        state.active++;
        state.reserve = 55 - count(state.active);
      } else {
        state.active = 0;
        beginTurn();
      }
    } else {
      check(
          state.players.get(state.active).cards.size() < 5,
          "Trade cards while holding five or more");
      state.phase = ATTACK;
      state.returnToAttack = false;
    }
    changed();
  }

  public void attack(int from, int to, int dice) {
    check(canAttack(from, to), "Choose adjacent enemy territory and leave one army behind");
    check(dice >= 1 && dice <= Math.min(3, state.armies[from] - 1), "Invalid attack dice");
    int defender = state.owners[to];
    state.attackDice = roll(dice);
    state.defendDice = roll((int) Math.min(2, state.armies[to]));
    int[] losses = losses(state.attackDice, state.defendDice);
    state.armies[from] -= losses[0];
    state.armies[to] -= losses[1];
    if (state.armies[to] == 0) {
      state.owners[to] = state.active;
      state.phase = OCCUPY;
      state.occupationFrom = from;
      state.occupationTo = to;
      state.occupationMinimum = dice;
      state.conquered = true;
      String failed =
          state.players.get(state.active).name
              + " attacked "
              + state.players.get(defender).name
              + " at "
              + name(to)
              + " without capturing it.";
      state.history.removeIf(n -> n.createdTurn == state.turn && n.message.equals(failed));
      log(
          state.players.get(state.active).name
              + " attacked "
              + state.players.get(defender).name
              + " and won "
              + name(to)
              + ".");
      if (!alive(defender)) {
        state.players.get(state.active).cards.addAll(state.players.get(defender).cards);
        state.players.get(defender).cards.clear();
        for (int p = 0; p < 5; p++) state.borders[defender][p] = state.borders[p][defender] = false;
        log(
            state.players.get(defender).name
                + " was eliminated; their cards transfer to "
                + state.players.get(state.active).name
                + ".");
      }
    } else {
      String summary =
          state.players.get(state.active).name
              + " attacked "
              + state.players.get(defender).name
              + " at "
              + name(to)
              + " without capturing it.";
      boolean already =
          state.history.stream()
              .anyMatch(n -> n.createdTurn == state.turn && n.message.equals(summary));
      if (!already) log(summary);
    }
    changed();
  }

  public static int[] losses(int[] attacker, int[] defender) {
    check(
        attacker.length >= 1
            && attacker.length <= 3
            && defender.length >= 1
            && defender.length <= 2,
        "Invalid dice");
    int[] a = attacker.clone(), d = defender.clone();
    Arrays.sort(a);
    Arrays.sort(d);
    int al = 0, dl = 0;
    for (int i = 1; i <= Math.min(a.length, d.length); i++) {
      if (a[a.length - i] > d[d.length - i]) dl++;
      else al++;
    }
    return new int[] {al, dl};
  }

  private int[] roll(int n) {
    int[] d = new int[n];
    for (int i = 0; i < n; i++) d[i] = 1 + random(6);
    return d;
  }

  public void occupy(long amount) {
    check(state.phase == OCCUPY, "No occupation pending");
    int from = state.occupationFrom, to = state.occupationTo;
    check(
        amount >= state.occupationMinimum && amount < state.armies[from],
        "Move at least the attacking dice count and leave one behind");
    state.armies[from] -= amount;
    state.armies[to] = amount;
    state.occupationFrom = state.occupationTo = -1;
    state.phase = ATTACK;
    if (count(state.active) == state.owners.length) {
      state.winner = state.active;
      state.phase = FINISHED;
      log(state.players.get(state.active).name + " conquered the world!");
    } else if (state.players.get(state.active).cards.size() >= 6) {
      state.phase = REINFORCE;
      state.reserve = 0;
      state.returnToAttack = true;
    }
    changed();
  }

  public void finishAttacks() {
    check(state.phase == ATTACK, "Finish deployment or occupation first");
    state.phase = FORTIFY;
    Arrays.fill(state.moved, 0);
    changed();
  }

  /**
   * Open borders permits transit during fortification, never ownership or attacking from allied
   * land.
   */
  public boolean connected(int from, int to, int player) {
    if (!validRegion(from)
        || !validRegion(to)
        || state.owners[from] != player
        || state.owners[to] != player) return false;
    boolean[] seen = new boolean[adjacent.length];
    ArrayDeque<Integer> q = new ArrayDeque<>();
    q.add(from);
    while (!q.isEmpty()) {
      int r = q.remove();
      if (r == to) return true;
      if (seen[r]) continue;
      seen[r] = true;
      for (int n : adjacent[r])
        if (!seen[n] && (state.owners[n] == player || state.borders[player][state.owners[n]]))
          q.add(n);
    }
    return false;
  }

  public void fortify(int from, int to, long amount) {
    check(state.phase == FORTIFY, "Fortify at the end of your turn");
    own(from);
    own(to);
    check(
        from != to && amount > 0 && amount <= movable(from) && connected(from, to, state.active),
        "Use connected friendly territory; each army may move only once");
    long next = Math.addExact(state.armies[to], amount);
    state.armies[from] -= amount;
    state.armies[to] = next;
    state.moved[to] = Math.addExact(state.moved[to], amount);
    changed();
  }

  public void endTurn() {
    check(state.phase == FORTIFY, "Finish attacking first");
    if (state.conquered) drawCard();
    state.active = (state.active + 1) % 5;
    while (!alive(state.active)) state.active = (state.active + 1) % 5;
    beginTurn();
    changed();
  }

  private void beginTurn() {
    state.turn++;
    state.phase = REINFORCE;
    state.conquered = false;
    state.returnToAttack = false;
    Arrays.fill(state.moved, 0);
    state.attackDice = new int[0];
    state.defendDice = new int[0];
    state.reserve = income(state.active);
    log(
        state.players.get(state.active).name
            + " begins turn "
            + state.turn
            + " with "
            + state.reserve
            + " reinforcements.");
  }

  private void drawCard() {
    if (state.deck.isEmpty()) {
      state.deck.addAll(state.discard);
      state.discard.clear();
      shuffle(state.deck);
    }
    if (!state.deck.isEmpty())
      state.players.get(state.active).cards.add(state.deck.remove(state.deck.size() - 1));
  }

  public int symbol(int card) {
    return card >= state.owners.length ? 3 : card % 3;
  }

  public boolean validSet(List<Integer> cards) {
    if (cards.size() != 3 || new HashSet<>(cards).size() != 3) return false;
    int[] c = new int[3];
    int wild = 0;
    for (int id : cards) {
      if (id < 0 || id >= state.owners.length + 2) return false;
      int s = symbol(id);
      if (s == 3) wild++;
      else c[s]++;
    }
    return Arrays.stream(c).max().orElse(0) + wild == 3
        || Arrays.stream(c).filter(x -> x > 0).count() + wild == 3;
  }

  public List<Integer> findSet(int player) {
    List<Integer> cards = state.players.get(player).cards;
    for (int a = 0; a < cards.size(); a++)
      for (int b = a + 1; b < cards.size(); b++)
        for (int c = b + 1; c < cards.size(); c++) {
          List<Integer> set = List.of(cards.get(a), cards.get(b), cards.get(c));
          if (validSet(set)) return set;
        }
    return List.of();
  }

  public long nextSetValue() {
    return state.tradeCount < 5 ? 4 + state.tradeCount * 2 : 15L + (state.tradeCount - 5) * 5L;
  }

  public void tradeCards(List<Integer> cards) {
    check(state.phase == REINFORCE, "Trade at the start of a turn or after an elimination");
    List<Integer> hand = state.players.get(state.active).cards;
    check(validSet(cards) && hand.containsAll(cards), "Select a valid three-card set");
    long reward = nextSetValue();
    long reserve = Math.addExact(state.reserve, reward);
    int bonus = -1;
    for (int c : cards)
      if (c < state.owners.length && state.owners[c] == state.active) {
        bonus = c;
        break;
      }
    if (bonus >= 0) Math.addExact(state.armies[bonus], 2);
    hand.removeAll(cards);
    state.discard.addAll(cards);
    state.tradeCount++;
    state.reserve = reserve;
    if (bonus >= 0) state.armies[bonus] += 2;
    log(state.players.get(state.active).name + " traded a card set for " + reward + " armies.");
    changed();
  }

  public GameState.Offer sendOffer(GameState.Offer draft) {
    GameState.Offer o = createOffer(draft);
    changed();
    return o;
  }

  private GameState.Offer createOffer(GameState.Offer draft) {
    check(
        state.phase != SETUP && state.phase != OCCUPY && state.phase != FINISHED,
        "Finish setup or occupation first");
    check(
        draft.from == state.active
            && draft.to >= 0
            && draft.to < 5
            && draft.to != draft.from
            && alive(draft.to),
        "Choose another active player");
    GameState.Offer offer = copyOffer(draft);
    check(
        offer.openBorders
            || !offer.giveTerritories.isEmpty()
            || !offer.wantTerritories.isEmpty()
            || offer.giveArmies > 0
            || offer.wantArmies > 0,
        "Offer is empty");
    check(offerValid(offer), "Offer terms are not currently possible");
    check(
        state.offers.stream()
                .filter(o -> o.from == state.active && o.status == GameState.Offer.Status.PENDING)
                .count()
            < 12,
        "At most twelve pending offers");
    offer.id = state.nextOfferId++;
    offer.createdTurn = state.turn;
    state.offers.add(offer);
    return offer;
  }

  private GameState.Offer copyOffer(GameState.Offer d) {
    GameState.Offer o = new GameState.Offer();
    o.from = d.from;
    o.to = d.to;
    o.giveTerritories.addAll(d.giveTerritories);
    o.wantTerritories.addAll(d.wantTerritories);
    o.giveSource = d.giveSource;
    o.giveTarget = d.giveTarget;
    o.giveArmies = d.giveArmies;
    o.wantSource = d.wantSource;
    o.wantTarget = d.wantTarget;
    o.wantArmies = d.wantArmies;
    o.openBorders = d.openBorders;
    return o;
  }

  public List<GameState.Offer> inbox() {
    return state.offers.stream()
        .filter(o -> o.to == state.active && o.status == GameState.Offer.Status.PENDING)
        .collect(java.util.stream.Collectors.toList());
  }

  public List<GameState.Offer> responses() {
    return state.offers.stream()
        .filter(
            o ->
                o.from == state.active
                    && o.status != GameState.Offer.Status.PENDING
                    && o.responseTurn > state.lastReadTurn[state.active])
        .collect(java.util.stream.Collectors.toList());
  }

  public void markRead() {
    state.lastReadTurn[state.active] = state.turn;
    changed();
  }

  public boolean offerValid(GameState.Offer o) {
    if (o.from < 0
        || o.from >= 5
        || o.to < 0
        || o.to >= 5
        || o.from == o.to
        || !alive(o.from)
        || !alive(o.to)) return false;
    if (new HashSet<>(o.giveTerritories).size() != o.giveTerritories.size()
        || new HashSet<>(o.wantTerritories).size() != o.wantTerritories.size()) return false;
    for (int r : o.giveTerritories) if (!validRegion(r) || state.owners[r] != o.from) return false;
    for (int r : o.wantTerritories) if (!validRegion(r) || state.owners[r] != o.to) return false;
    // Neither side may be eliminated by diplomacy. This also guarantees a refuge for all garrisons.
    if (count(o.from) - o.giveTerritories.size() < 1 || count(o.to) - o.wantTerritories.size() < 1)
      return false;
    if (!armyGiftValid(o.giveSource, o.giveTarget, o.giveArmies, o.from, o.to, o)
        || !armyGiftValid(o.wantSource, o.wantTarget, o.wantArmies, o.to, o.from, o)) return false;
    if (o.giveArmies < 0 || o.wantArmies < 0) return false;
    try {
      prepareExchange(o);
      return true;
    } catch (IllegalArgumentException | ArithmeticException e) {
      return false;
    }
  }

  private boolean armyGiftValid(
      int source, int target, long amount, int from, int to, GameState.Offer o) {
    if (amount == 0) return true;
    return amount > 0
        && validRegion(source)
        && validRegion(target)
        && state.owners[source] == from
        && state.owners[target] == to
        && !o.giveTerritories.contains(source)
        && !o.wantTerritories.contains(source)
        && !o.giveTerritories.contains(target)
        && !o.wantTerritories.contains(target)
        && adjacent(source, target)
        && state.armies[source] > amount;
  }

  public boolean answer(int id, boolean accept) {
    GameState.Offer o = offer(id);
    check(
        o.to == state.active && o.status == GameState.Offer.Status.PENDING,
        "Offer is not in your inbox");
    check(
        state.phase == REINFORCE || state.phase == ATTACK || state.phase == FORTIFY,
        "Cannot answer now");
    if (!accept) {
      o.status = GameState.Offer.Status.REJECTED;
      o.responseTurn = state.turn;
      changed();
      return false;
    }
    if (!offerValid(o)) {
      o.status = GameState.Offer.Status.INVALID;
      o.responseTurn = state.turn;
      changed();
      return false;
    }
    Exchange exchange = prepareExchange(o);
    int[] owners = exchange.owners();
    long[] armies = exchange.armies();
    // Preserve movement history for unaffected armies; only arrivals become spent.
    long[] oldArmies = state.armies;
    int[] oldOwners = state.owners;
    state.owners = owners;
    state.armies = armies;
    if (state.phase == FORTIFY)
      for (int i = 0; i < armies.length; i++)
        if (owners[i] == state.active) {
          state.moved[i] =
              oldOwners[i] != state.active
                  ? Math.max(0, armies[i] - 1)
                  : Math.min(
                      Math.max(0, armies[i] - 1),
                      state.moved[i] + Math.max(0, armies[i] - oldArmies[i]));
        }
    if (o.openBorders) state.borders[o.from][o.to] = state.borders[o.to][o.from] = true;
    o.status = GameState.Offer.Status.ACCEPTED;
    o.responseTurn = state.turn;
    changed();
    return true;
  }

  private record Exchange(int[] owners, long[] armies) {}

  private Exchange prepareExchange(GameState.Offer o) {
    int[] owners = state.owners.clone();
    long[] armies = state.armies.clone();
    if (o.giveArmies > 0) {
      armies[o.giveSource] -= o.giveArmies;
      armies[o.giveTarget] = Math.addExact(armies[o.giveTarget], o.giveArmies);
    }
    if (o.wantArmies > 0) {
      armies[o.wantSource] -= o.wantArmies;
      armies[o.wantTarget] = Math.addExact(armies[o.wantTarget], o.wantArmies);
    }
    for (int r : o.giveTerritories) owners[r] = o.to;
    for (int r : o.wantTerritories) owners[r] = o.from;
    List<Integer> transferred = new ArrayList<>(o.giveTerritories);
    transferred.addAll(o.wantTerritories);
    long[] returning = armies.clone();
    // Every incoming land gets a garrison from its new owner's unchanged land.
    // Original garrisons all return to their original owner; no army is minted or converted.
    for (int source : transferred) {
      int receiving = owners[source], donor = -1;
      for (int r = 0; r < owners.length; r++)
        if (!transferred.contains(r)
            && owners[r] == receiving
            && state.owners[r] == receiving
            && armies[r] > 1) {
          donor = r;
          break;
        }
      check(donor >= 0, "The recipient needs a spare army to garrison each incoming territory");
      armies[donor]--;
      armies[source] = 1;
    }
    for (int source : transferred)
      redistribute(source, state.owners[source], returning[source], owners, armies);
    return new Exchange(owners, armies);
  }

  private void redistribute(int source, int player, long total, int[] owners, long[] armies) {
    Set<Integer> destinations = new TreeSet<>();
    for (int r : adjacent[source]) if (owners[r] == player) destinations.add(r);
    if (destinations.isEmpty()) {
      boolean[] seen = new boolean[owners.length];
      ArrayDeque<Integer> q = new ArrayDeque<>();
      q.add(source);
      seen[source] = true;
      while (!q.isEmpty() && destinations.isEmpty()) {
        int n = q.size();
        while (n-- > 0) {
          int r = q.remove();
          if (owners[r] == player) destinations.add(r);
          else
            for (int next : adjacent[r])
              if (!seen[next]) {
                seen[next] = true;
                q.add(next);
              }
        }
      }
    }
    check(!destinations.isEmpty(), "No territory available for returning armies");
    int i = 0;
    for (int r : destinations) {
      armies[r] =
          Math.addExact(
              armies[r], total / destinations.size() + (i < total % destinations.size() ? 1 : 0));
      i++;
    }
  }

  public GameState.Offer counter(int id, GameState.Offer replacement) {
    GameState.Offer old = offer(id);
    check(
        old.to == state.active && old.status == GameState.Offer.Status.PENDING,
        "Offer is not pending");
    check(
        replacement.from == old.to && replacement.to == old.from,
        "Counteroffer must reverse the participants");
    GameState.Offer next = createOffer(replacement);
    old.status = GameState.Offer.Status.COUNTERED;
    old.responseTurn = state.turn;
    changed();
    return next;
  }

  public void revokeBorders(int other) {
    check(other >= 0 && other < 5 && other != state.active, "Invalid player");
    state.borders[state.active][other] = state.borders[other][state.active] = false;
    changed();
  }

  public GameState.Offer offer(int id) {
    return state.offers.stream()
        .filter(o -> o.id == id)
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown offer"));
  }

  public List<String> report(int player) {
    List<String> lines = new ArrayList<>();
    for (GameState.Notice n : state.history)
      if (n.createdTurn > state.lastReadTurn[player]) lines.add(n.message);
    for (GameState.Offer o : responses())
      lines.add("Offer #" + o.id + " to " + state.players.get(o.to).name + ": " + o.status);
    return lines;
  }

  private void log(String message) {
    state.history.add(new GameState.Notice(message, state.turn));
    if (state.history.size() > 1000) state.history.remove(0);
  }

  public int random(int bound) {
    check(bound > 0, "Invalid random range");
    long x = state.rng;
    x ^= x << 13;
    x ^= x >>> 7;
    x ^= x << 17;
    state.rng = x;
    return (int) Long.remainderUnsigned(x, bound);
  }

  private void shuffle(List<Integer> values) {
    for (int i = values.size() - 1; i > 0; i--) {
      int j = random(i + 1);
      Collections.swap(values, i, j);
    }
  }

  private boolean validRegion(int r) {
    return r >= 0 && r < state.owners.length;
  }

  private void region(int r) {
    check(validRegion(r), "Unknown territory");
  }

  private void own(int r) {
    region(r);
    check(state.owners[r] == state.active, "Choose your own territory");
  }

  static void check(boolean condition, String message) {
    if (!condition) throw new IllegalArgumentException(message);
  }

  public void validate() {
    check(state.version == 1 && state.players.size() == 5, "Unsupported or corrupt save");
    check(
        state.active >= 0
            && state.active < 5
            && state.phase != null
            && state.difficulty != null
            && state.rng != 0,
        "Invalid turn state");
    check(
        state.territoryIds.equals(
            board.regions.stream().map(r -> r.id).collect(java.util.stream.Collectors.toList())),
        "Save belongs to a different board");
    check(
        state.owners.length == board.regions.size()
            && state.armies.length == state.owners.length
            && state.moved.length == state.owners.length,
        "Invalid territory data");
    check(state.reserve >= 0 && state.tradeCount >= 0, "Invalid economy");
    check(state.borders.length == 5 && state.lastReadTurn.length == 5, "Invalid diplomacy state");
    for (int p = 0; p < 5; p++) {
      check(state.borders[p].length == 5 && !state.borders[p][p], "Invalid borders");
      for (int q = 0; q < 5; q++)
        check(state.borders[p][q] == state.borders[q][p], "Asymmetric borders");
    }
    for (int i = 0; i < state.owners.length; i++) {
      check(state.owners[i] >= 0 && state.owners[i] < 5, "Invalid owner");
      check(
          state.armies[i] >= 0
              && (state.armies[i] > 0 || state.phase == OCCUPY && state.occupationTo == i),
          "Invalid army count");
      check(state.moved[i] >= 0, "Invalid movement state");
    }
    check(alive(state.active), "Active player has no territory");
    if (state.phase == OCCUPY)
      check(
          validRegion(state.occupationFrom)
              && validRegion(state.occupationTo)
              && state.occupationMinimum >= 1
              && state.occupationMinimum <= 3
              && state.owners[state.occupationFrom] == state.active
              && state.owners[state.occupationTo] == state.active
              && adjacent(state.occupationFrom, state.occupationTo)
              && state.armies[state.occupationFrom] > state.occupationMinimum
              && state.armies[state.occupationTo] == 0,
          "Invalid occupation");
    if (state.phase == FINISHED)
      check(
          state.winner == state.active && count(state.active) == state.owners.length,
          "Invalid winner");
    Set<Integer> ids = new HashSet<>();
    for (GameState.Player player : state.players)
      check(
          player.name != null
              && !player.name.trim().isEmpty()
              && player.objective != null
              && player.cards != null,
          "Invalid player");
    for (GameState.Offer o : state.offers) {
      check(
          o.id > 0
              && ids.add(o.id)
              && o.id < state.nextOfferId
              && o.from >= 0
              && o.from < 5
              && o.to >= 0
              && o.to < 5
              && o.from != o.to
              && o.status != null,
          "Invalid saved offer");
      check(o.giveArmies >= 0 && o.wantArmies >= 0, "Invalid saved gift");
      for (int r : o.giveTerritories) region(r);
      for (int r : o.wantTerritories) region(r);
      if (o.giveArmies > 0) {
        region(o.giveSource);
        region(o.giveTarget);
      }
      if (o.wantArmies > 0) {
        region(o.wantSource);
        region(o.wantTarget);
      }
    }
    for (GameState.Notice n : state.history)
      check(n.message != null && n.createdTurn <= state.turn, "Invalid report");
    Set<Integer> cards = new HashSet<>();
    for (GameState.Player p : state.players)
      for (int c : p.cards)
        check(c >= 0 && c < state.owners.length + 2 && cards.add(c), "Duplicate card");
    for (int c : state.deck)
      check(c >= 0 && c < state.owners.length + 2 && cards.add(c), "Duplicate deck card");
    for (int c : state.discard)
      check(c >= 0 && c < state.owners.length + 2 && cards.add(c), "Duplicate discard");
    check(cards.size() == state.owners.length + 2, "Missing cards");
  }
}
