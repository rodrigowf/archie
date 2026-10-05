/** "Good morning." / "Good afternoon." / "Good evening." (mockups phone (i)). */
export function greeting(now: Date = new Date()): string {
  const h = now.getHours();
  if (h >= 5 && h < 12) return 'Good morning.';
  if (h >= 12 && h < 18) return 'Good afternoon.';
  return 'Good evening.';
}
