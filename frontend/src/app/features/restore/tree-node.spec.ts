import { describe, expect, it } from 'vitest';
import { TreeNode, flatten, toNode } from './tree-node';

function directory(name: string, level: number, children?: TreeNode[]): TreeNode {
  return {
    path: '/' + name,
    name,
    directory: true,
    sizeBytes: null,
    modifiedAt: null,
    level,
    expanded: children !== undefined,
    loading: false,
    children,
  };
}

describe('flatten', () => {
  it('zeigt nur die Wurzel, solange nichts aufgeklappt ist', () => {
    const tree = [directory('daten', 0)];

    expect(flatten(tree)).toHaveLength(1);
  });

  it('nimmt die Kinder eines aufgeklappten Verzeichnisses mit', () => {
    const tree = [
      directory('daten', 0, [
        toNode(
          { path: '/daten/a.txt', name: 'a.txt', directory: false, sizeBytes: 1, modifiedAt: null },
          1,
        ),
      ]),
    ];

    expect(flatten(tree).map((node) => node.name)).toEqual(['daten', 'a.txt']);
  });

  it('lässt die Kinder eines zugeklappten Verzeichnisses weg', () => {
    // Sonst stünde ein Snapshot mit Hunderttausenden Dateien vollständig in der Anzeige.
    const tree = [directory('daten', 0, [directory('unterordner', 1, [])])];
    tree[0].expanded = false;

    expect(flatten(tree)).toHaveLength(1);
  });

  it('geht über mehrere Ebenen', () => {
    const innen = directory('unterordner', 1, [
      toNode(
        {
          path: '/daten/unterordner/b.md',
          name: 'b.md',
          directory: false,
          sizeBytes: 2,
          modifiedAt: null,
        },
        2,
      ),
    ]);
    const tree = [directory('daten', 0, [innen])];

    expect(flatten(tree).map((node) => node.level)).toEqual([0, 1, 2]);
  });
});
