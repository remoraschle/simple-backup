import { SnapshotEntry } from '../../core/api/models';

/**
 * Ein Eintrag im Baum, flach gehalten statt verschachtelt.
 *
 * <p>Eine flache Liste mit Ebenenangabe lässt sich in einer Tabelle zeigen und bleibt
 * berechenbar: Auf- und Zuklappen heißt Zeilen einfügen und entfernen, nicht einen Baum
 * umbauen. Angular Material bringt keine Baumtabelle mit; diese hier ist die kleinste
 * Lösung, die das Blättern in einem Snapshot trägt.
 */
export interface TreeNode {
  readonly path: string;
  readonly name: string;
  readonly directory: boolean;
  readonly sizeBytes: number | null;
  readonly modifiedAt: string | null;
  /** Tiefe im Baum, für die Einrückung. Die Wurzel liegt auf 0. */
  readonly level: number;
  expanded: boolean;
  loading: boolean;
  /** Erst gesetzt, wenn das Verzeichnis einmal geöffnet wurde. */
  children?: TreeNode[];
}

export function toNode(entry: SnapshotEntry, level: number): TreeNode {
  return {
    path: entry.path,
    name: entry.name,
    directory: entry.directory,
    sizeBytes: entry.sizeBytes,
    modifiedAt: entry.modifiedAt,
    level,
    expanded: false,
    loading: false,
  };
}

/**
 * Macht aus dem Baum die Liste der gerade sichtbaren Zeilen.
 *
 * <p>Zugeklappte Verzeichnisse nehmen ihre Kinder mit — sonst stünde ein Snapshot mit
 * Hunderttausenden Dateien vollständig im Speicher der Anzeige.
 */
export function flatten(nodes: TreeNode[]): TreeNode[] {
  const visible: TreeNode[] = [];

  for (const node of nodes) {
    visible.push(node);
    if (node.expanded && node.children) {
      visible.push(...flatten(node.children));
    }
  }
  return visible;
}
