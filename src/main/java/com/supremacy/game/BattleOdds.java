package com.supremacy.game;

/** Exact maximum-dice conquest odds for ordinary battles; bounded approximation for huge armies. */
final class BattleOdds {
  private static final int LIMIT = 180;
  private static final double[][] WIN = new double[LIMIT + 1][LIMIT + 1];

  static {
    double[][][] outcomes = new double[4][3][3];
    for (int a = 1; a <= 3; a++)
      for (int d = 1; d <= 2; d++) {
        int combinations = (int) Math.pow(6, a + d);
        for (int roll = 0; roll < combinations; roll++) {
          int code = roll;
          int[] attack = new int[a], defend = new int[d];
          for (int i = 0; i < a; i++) {
            attack[i] = code % 6 + 1;
            code /= 6;
          }
          for (int i = 0; i < d; i++) {
            defend[i] = code % 6 + 1;
            code /= 6;
          }
          outcomes[a][d][GameEngine.losses(attack, defend)[0]] += 1.0 / combinations;
        }
      }
    for (int a = 1; a <= LIMIT; a++) WIN[a][0] = 1;
    for (int a = 1; a <= LIMIT; a++)
      for (int d = 1; d <= LIMIT; d++) {
        int ad = Math.min(3, a), dd = Math.min(2, d), comparisons = Math.min(ad, dd);
        for (int loss = 0; loss <= comparisons; loss++)
          WIN[a][d] += outcomes[ad][dd][loss] * WIN[a - loss][d - (comparisons - loss)];
      }
  }

  private BattleOdds() {}

  /** Attackers excludes the mandatory source garrison. Does not consume game randomness. */
  static double win(double attackers, double defenders) {
    if (defenders <= 0) return 1;
    if (attackers < 1) return 0;
    if (attackers <= LIMIT && defenders <= LIMIT)
      return WIN[(int) attackers][(int) Math.ceil(defenders)];
    // Large battles concentrate around the three-versus-two attrition rate.
    double margin = (attackers - .86 * defenders) / Math.sqrt(attackers + defenders);
    return 1 / (1 + Math.exp(-1.4 * margin));
  }
}
