package com.thaap.payments.bulk;

import com.thaap.payments.support.WorkspacePostgreSqlTestBase;

/**
 * Review-bulk PostgreSQL proofs share the workspace-wide container and context.
 *
 * <p>This type exists only so the bulk proofs keep a package-local name; all of the container and
 * context sharing that `ER-0094` and `ER-0095` required lives in
 * {@link WorkspacePostgreSqlTestBase}, which other capabilities extend too.
 */
abstract class ReviewBulkPostgreSqlTestBase extends WorkspacePostgreSqlTestBase {
}
