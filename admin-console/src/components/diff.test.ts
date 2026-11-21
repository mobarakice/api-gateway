import { describe, expect, it } from 'vitest';
import { diffLines, pretty } from './diff';

describe('diffLines', () => {
  it('marks added, removed and kept lines', () => {
    expect(diffLines('a\nb\nc', 'a\nc\nd')).toEqual([
      { op: ' ', text: 'a' }, { op: '-', text: 'b' }, { op: ' ', text: 'c' }, { op: '+', text: 'd' },
    ]);
    expect(diffLines('', 'x')).toEqual([{ op: '+', text: 'x' }]);
    expect(diffLines('x', '')).toEqual([{ op: '-', text: 'x' }]);
  });

  it('pretty-prints with sorted keys and without bookkeeping fields', () => {
    expect(pretty({ b: 1, a: { d: 2, c: 3 }, updatedAt: 'x', updatedBy: 'y' })).toBe(
      '{\n  "a": {\n    "c": 3,\n    "d": 2\n  },\n  "b": 1\n}');
    expect(pretty(null)).toBe('');
  });
});
