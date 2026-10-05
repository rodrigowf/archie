import { afterEach, describe, expect, it } from 'vitest';
import { renderUi } from '@/test/render';
import { expectNoAxeViolations } from '@/test/axe';
import { Gallery, collectSections } from './Gallery';
import { galleryHref, isGalleryRoute, parseGalleryRoute } from './route';

describe('gallery route', () => {
  it('matches #/dev/gallery with an optional section and query', () => {
    expect(isGalleryRoute('#/dev/gallery')).toBe(true);
    expect(isGalleryRoute('#/dev/gallery/colors?theme=light')).toBe(true);
    expect(isGalleryRoute('#/dev/galleryx')).toBe(false);
    expect(isGalleryRoute('')).toBe(false);
    expect(parseGalleryRoute('#/dev/gallery/typography?theme=light&full=1')).toEqual({
      section: 'typography',
      theme: 'light',
      full: true,
    });
    expect(parseGalleryRoute('#/dev/gallery?theme=blue')).toEqual({ section: null, theme: null, full: false });
    expect(galleryHref('icons', 'dark')).toBe('#/dev/gallery/icons?theme=dark');
  });
});

describe('Gallery', () => {
  afterEach(() => {
    window.location.hash = '';
  });

  it('discovers the W-02 sections', () => {
    const ids = collectSections().map((x) => x.id);
    expect(ids).toEqual(expect.arrayContaining(['colors', 'typography', 'shape', 'icons', 'primitives']));
  });

  it('renders one section from the route, with the theme switch, and no axe violations', async () => {
    window.location.hash = '#/dev/gallery/primitives?theme=light';
    const { getByRole, container, user } = renderUi(<Gallery />);
    expect(getByRole('heading', { level: 2, name: 'Primitives' })).toBeTruthy();
    expect(container.querySelectorAll('section')).toHaveLength(1);
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    await user.click(getByRole('button', { name: 'Dark' }));
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
    expect(getByRole('button', { name: 'Dark' }).getAttribute('aria-pressed')).toBe('true');
    await expectNoAxeViolations(container);
  });
});
