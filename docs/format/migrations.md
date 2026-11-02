# Migrations

A package keeps data in its own SQLite database (`nf.db()`, see
[`Database`](../reference/database.md)), which the package asks for in its
`netherforge.json` with `"requires": { "db": true }` (see
[Requirements](project.md#requirements)); without it, `nf.db()` is an error
naming the package. A **migration** is one step of that
database's schema: a file `migrations/NNN_name.sql` holding plain SQL, with
nothing beside it.

```sql
-- migrations/001_init.sql
CREATE TABLE scores (
  id INTEGER PRIMARY KEY,
  name TEXT NOT NULL UNIQUE,
  score INTEGER NOT NULL
);
```

```sql
-- migrations/002_rank.sql
ALTER TABLE scores ADD COLUMN rank INTEGER;
```

## Names and numbers

A migration is named `NNN_name.sql`: three digits, then lowercase letters,
digits and `_` (the id, `001_init`, follows the usual [id](project.md#ids)
rules). The numbers run `001`, `002`, `003` with none skipped and none used
twice, so the order is never a guess.

| What                                                                | Problem               |
| ------------------------------------------------------------------- | --------------------- |
| A file that isn't `NNN_name.sql` (`init.sql`)                       | `migration.name`      |
| Two files with the same number                                      | `migration.duplicate` |
| A number that doesn't follow the one before                         | `migration.gap`       |
| A server's version without its SQLite one, or a dialect missing one | `migration.dialects`  |

The numbers are also the migration's identity in the database, which
records the ones it has had. Add a new file for each change; never reuse a
number.

## On a MySQL or PostgreSQL server

A server's owner can put a package's own database on a MySQL or PostgreSQL
server instead of a SQLite file (`own-databases`, see
[Databases](../guide/server-config.md#a-package-s-own-database-on-your-server)),
so several servers of one project share it. Its scripts don't change; its
migrations are then that server's versions of each step, beside the SQLite
ones with the same names:

```
migrations/
  001_init.sql            SQLite's, which every package starts on
  002_rank.sql
  postgres/
    001_init.sql          the same steps in PostgreSQL's SQL
    002_rank.sql
  mysql/
    001_init.sql
    002_rank.sql
```

A dialect has a version of every migration or of none, so the steps are the
same on every server; a version with no SQLite migration beside it, or a
migration a dialect lacks while others have it, is `migration.dialects`. A
package whose own database is put on a server it has no versions for is
refused there (`migration.dialect`). On a shared server the migrations run
once across every server, under a lock, and are recorded in the package's
space's `nf_migrations` table; a server that finds a migration applied that
its copy of the package doesn't have refuses the package (`migration.ahead`).
A MySQL version may hold several statements (they're run one at a time);
MySQL commits each schema statement as it runs, so a MySQL migration that
fails part-way can leave its first statements applied.

## Not on a connection opened by name

Migrations never run on a named MySQL or PostgreSQL connection opened with
`nf.db("network")`: a shared server's tables may belong to other packages or
tools. A package that keeps tables on a shared connection
creates them itself (`CREATE TABLE IF NOT EXISTS shop_scores (...)` through
`db:execute`, in that server's dialect), or leaves the schema to whoever runs
the server. See [Databases](../guide/server-config.md#databases).

## What the server does with them

When the project loads (and again when a migration file is added on a reload),
the plugin applies each package's migrations, in number order, to that
package's database: `plugins/NetherForge/databases/<namespace>.db`, a file of
the package's own (a dependency has its own, apart from the project's). Each
migration runs once, in a transaction of its own, and is recorded in the
database's `migrations` table, so a restart applies only the new ones. A
migration is one file but may hold several statements.

Migrations only go forward. Editing a migration the database has already had
changes nothing (the database doesn't run it again), and deleting one doesn't
undo it; the server's log says when a database has had migrations the files no
longer have. To start a development database over, stop the dev server and
delete its file.

If a migration can't be applied (the SQL is wrong, or the files have the
problems above), the package's database is **unavailable**: its scripts'
`nf.db()` calls answer `nil, err` with the reason, and the migration's file
carries a problem, `migration.failed`. Migrations before the failing one stay
applied. Fixing the file and saving it migrates the database again, no restart
needed. The rest of the project keeps running: only the database waits.
