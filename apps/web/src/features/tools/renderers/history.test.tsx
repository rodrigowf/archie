import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ToolCard } from '../ToolCard';
import { tool } from '../__fixtures__/blocks';
import { parseConversationRead, parseHistorySearch } from './history';

const SEARCH = JSON.stringify({
  query: 'turin',
  sessions: [
    {
      session_id: '96a377c7-8910-450b-88b9-5fa4889a94c7',
      title: 'qvcm and bicameral mind',
      started_at: '2026-06-20T10:00:00Z',
      relevance: 'strong',
      copies: ['a', 'b'],
      hits: [{ turn: 40, role: 'user', date: '2026-06-21T12:04:00Z', match: 'keyword', text: 'ancient rituals as tools' }],
    },
    { session_id: 'x', title: null, relevance: 'weak', note: 'mostly about searching', hits: [] },
  ],
});

describe('parseHistorySearch', () => {
  it('reads the session-grouped shape', () => {
    const r = parseHistorySearch(SEARCH);
    expect(r?.sessions.map((s) => [s.title, s.relevance, s.copies, s.hits.length])).toEqual([
      ['qvcm and bicameral mind', 'strong', 2, 1],
      [null, 'weak', 0, 0],
    ]);
  });

  it('returns null for the old chunk-list shape and for non-JSON', () => {
    expect(parseHistorySearch('{"results": []}')).toBeNull();
    expect(parseHistorySearch('3 matches')).toBeNull();
  });
});

describe('parseConversationRead', () => {
  it('reads turns, and errors', () => {
    const r = parseConversationRead(JSON.stringify({ title: 't', total_turns: 9, turns: [{ turn: 3, role: 'assistant', text: 'hi' }] }));
    expect(r?.turns).toEqual([{ turn: 3, role: 'assistant', date: null, text: 'hi' }]);
    expect(parseConversationRead('{"error": "No conversation file"}')?.error).toBe('No conversation file');
    expect(parseConversationRead('nope')).toBeNull();
  });
});

describe('history cards', () => {
  it('search_history lists sessions with their excerpts', () => {
    render(<ToolCard block={tool('search_history', { query: 'turin' }, { output: SEARCH })} sessionKind="orchestrator" autoOpen />);
    expect(screen.getByText('qvcm and bicameral mind')).toBeTruthy();
    expect(screen.getByText('2026-06-20 · strong · 96a377c7 · +2 copies')).toBeTruthy();
    expect(screen.getByText('ancient rituals as tools')).toBeTruthy();
    expect(screen.getByText('Untitled conversation')).toBeTruthy();
    expect(screen.getByText('mostly about searching')).toBeTruthy();
  });

  it('search_history falls back to the raw text for unknown output', () => {
    render(<ToolCard block={tool('search_history', { query: 'x' }, { output: 'legacy text' })} sessionKind="orchestrator" autoOpen />);
    expect(screen.getByText('legacy text')).toBeTruthy();
  });

  it('read_conversation shows the turn window', () => {
    const out = JSON.stringify({
      title: 'Living-room TV',
      total_turns: 40,
      turns: [
        { turn: 12, role: 'user', date: '2026-02-25T18:20:11Z', text: 'movie mode please' },
        { turn: 13, role: 'assistant', text: 'done' },
      ],
    });
    render(<ToolCard block={tool('read_conversation', { session_id: 'abc', turn: 12 }, { output: out })} sessionKind="orchestrator" autoOpen />);
    expect(screen.getByText('turns 12–13 of 40')).toBeTruthy();
    expect(screen.getByText('#12 user · 2026-02-25 18:20')).toBeTruthy();
    expect(screen.getByText('done')).toBeTruthy();
  });
});
