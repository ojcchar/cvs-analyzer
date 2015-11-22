package seers.cvsanalyzer.processor;

import java.util.List;
import java.util.Set;

import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.hibernate.Session;
import org.hibernate.Transaction;

import seers.appcore.threads.processor.ThreadException;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.appcore.utils.ExceptionUtils;
import seers.cvsanalyzer.git.GitUtilities2;
import seers.irda.dao.GenericDao;
import seers.irda.dao.impl.IssueDao;
import seers.irda.dao.impl.IssueRevisionDao;
import seers.irda.dao.impl.RevisionDao;
import seers.irda.entity.Issue;
import seers.irda.entity.IssueRevision;
import seers.irda.entity.IssueRevisionId;
import seers.irda.entity.Revision;
import seers.irda.entity.SoftwareSystem;

class PaginatedBugRevisionProcessor implements ThreadProcessor {

	private int pageSize, offset;
	private Repository repository;
	private String name;
	private SoftwareSystem system;

	public PaginatedBugRevisionProcessor(int pageSize, int offset, Repository repository, SoftwareSystem system) {
		super();
		this.pageSize = pageSize;
		this.offset = offset;
		this.repository = repository;
		this.system = system;
		name = this.getClass().getSimpleName() + "-" + offset + "-" + pageSize;
	}

	@Override
	public void processJob() throws ThreadException {

		Session session = GenericDao.openSession();
		try {

			// daos
			IssueDao iDao = new IssueDao(session);
			RevisionDao revDao = new RevisionDao(session);
			IssueRevisionDao isRevDao = new IssueRevisionDao(session);

			List<Issue> issues = iDao.getIssues(system, offset, pageSize);

			// ---------------------------------------

			Transaction tx = null;
			try {
				tx = session.beginTransaction();
				for (Issue issue : issues) {
					Set<IssueRevision> issueRevisions = issue.getIssueRevisions();
					for (IssueRevision issueRevision : issueRevisions) {

						Revision revision = issueRevision.getRevision();
						String commitId = revision.getCommitId();
						RevCommit revCommit = GitUtilities2.getRevision(commitId, repository);
						RevCommit prevCommit = revCommit.getParent(0);

						Revision prevRevision = revDao.getRevision(prevCommit.getName(), system);

						IssueRevisionId id = new IssueRevisionId(issue.getId(), prevRevision.getId(), "ORIG");
						IssueRevision issueRev = isRevDao.getIssueRevision(id);
						if (issueRev == null) {
							issueRev = new IssueRevision(id, issue, prevRevision);
							isRevDao.persist(issueRev);
						}

					}
				}

				tx.commit();
			} catch (Exception e) {
				if (tx != null)
					tx.rollback();
				throw e;
			}

		} catch (Exception e) {
			ThreadException e2 = new ThreadException(e.getMessage());
			ExceptionUtils.addStackTrace(e, e2);
			throw e2;
		} finally {
			session.close();
		}
	}

	@Override
	public String getName() {
		return name;
	}
}