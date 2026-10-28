/** An edit of the selected node, as the node sections get it. */
import { type NodeDef } from '@/core/format'

export type EditNode = (recipe: (node: NodeDef) => void) => void
