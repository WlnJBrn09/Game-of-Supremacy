package com.supremacy.game;

import java.util.ArrayList;

/** Versioned, platform-neutral save data. UI objects and Android types never enter a save. */
public final class GameState {
  public enum Phase {
    SETUP,
    REINFORCE,
    ATTACK,
    OCCUPY,
    FORTIFY,
    FINISHED
  }

  public enum Difficulty {
    Beginner,
    Easy,
    Medium,
    Hard,
    MegaHard,
    TeraHard
  }

  public static final String[] BOT_NAMES = {"Charles", "George", "Douglas", "Dan"};
  /** Selectable player colours (hex RGB). Every player holds a different one. */
  public static final String[] COLORS = {
    "D35E59", "4D9EC4", "D8B456", "81B581", "AA8EC5", "E08A4A", "4FB8A8", "DE7FA6"
  };

  public static final String[] COLOR_NAMES = {
    "Red", "Blue", "Gold", "Green", "Purple", "Orange", "Teal", "Pink"
  };
  public static final int MAX_NAME = 12;

  public static final class Player {
    public String name;
    public boolean human;
    // Index into COLORS; -1 only while reading a save written before colours were selectable.
    public int color = -1;
    public ArrayList<Integer> cards = new ArrayList<>();
    // A persistent strategic objective, reconsidered when the position changes.
    public String objective = "";

    public Player() {}
  }

  public static final class Notice {
    public String message;
    public int createdTurn;

    public Notice() {}

    public Notice(String message, int turn) {
      this.message = message;
      createdTurn = turn;
    }
  }

  public static final class Offer {
    public enum Status {
      PENDING,
      ACCEPTED,
      REJECTED,
      COUNTERED,
      INVALID
    }

    public int id, from, to, createdTurn, responseTurn = -1;
    public ArrayList<Integer> giveTerritories = new ArrayList<>(),
        wantTerritories = new ArrayList<>();
    public int giveSource = -1, giveTarget = -1, wantSource = -1, wantTarget = -1;
    public long giveArmies, wantArmies;
    public boolean openBorders;
    public Status status = Status.PENDING;

    public Offer() {}
  }

  public int version = 1, active, turn, winner = -1, tradeCount, nextOfferId = 1;
  public Phase phase = Phase.SETUP;
  public Difficulty difficulty = Difficulty.Medium;
  public ArrayList<Player> players = new ArrayList<>();
  public ArrayList<String> territoryIds = new ArrayList<>();
  public int[] owners;
  public long[] armies, moved;
  public long reserve, rng;
  public boolean conquered, returnToAttack;
  public int occupationFrom = -1, occupationTo = -1, occupationMinimum;
  public int[] attackDice = new int[0], defendDice = new int[0];
  public ArrayList<Integer> deck = new ArrayList<>(), discard = new ArrayList<>();
  public boolean[][] borders = new boolean[5][5];
  public ArrayList<Offer> offers = new ArrayList<>();
  public ArrayList<Notice> history = new ArrayList<>();
  public int[] lastReadTurn = {-1, -1, -1, -1, -1};

  public GameState() {}

  /** Hex RGB string of a player's chosen colour. */
  public String color(int player) {
    return COLORS[players.get(player).color];
  }
}
