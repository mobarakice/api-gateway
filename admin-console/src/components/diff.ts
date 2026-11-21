export type DiffLine = { op: ' ' | '+' | '-'; text: string };

/** Line diff (LCS). Objects are small (≤ 256 KiB), so O(n·m) on lines is fine. */
export function diffLines(before: string, after: string): DiffLine[] {
  const a = before === '' ? [] : before.split('\n');
  const b = after === '' ? [] : after.split('\n');
  const n = a.length;
  const m = b.length;
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array<number>(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    const row = lcs[i] as number[];
    const below = lcs[i + 1] as number[];
    for (let j = m - 1; j >= 0; j--) {
      row[j] = a[i] === b[j] ? (below[j + 1] ?? 0) + 1 : Math.max(below[j] ?? 0, row[j + 1] ?? 0);
    }
  }
  const out: DiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      out.push({ op: ' ', text: a[i] ?? '' });
      i++;
      j++;
    } else if ((lcs[i + 1]?.[j] ?? 0) >= (lcs[i]?.[j + 1] ?? 0)) {
      out.push({ op: '-', text: a[i] ?? '' });
      i++;
    } else {
      out.push({ op: '+', text: b[j] ?? '' });
      j++;
    }
  }
  for (; i < n; i++) {
    out.push({ op: '-', text: a[i] ?? '' });
  }
  for (; j < m; j++) {
    out.push({ op: '+', text: b[j] ?? '' });
  }
  return out;
}

/** Stable, readable JSON for diffs: keys sorted, envelope bookkeeping dropped. */
export function pretty(v: unknown): string {
  if (v === undefined || v === null) {
    return '';
  }
  const sort = (x: unknown): unknown => {
    if (Array.isArray(x)) {
      return x.map(sort);
    }
    if (typeof x === 'object' && x !== null) {
      return Object.fromEntries(Object.entries(x as Record<string, unknown>)
        .filter(([k]) => k !== 'updatedAt' && k !== 'updatedBy')
        .sort(([p], [q]) => (p < q ? -1 : p > q ? 1 : 0)).map(([k, y]) => [k, sort(y)]));
    }
    return x;
  };
  return JSON.stringify(sort(v), null, 2);
}
