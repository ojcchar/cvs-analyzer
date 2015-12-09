package seers.cvsanalyzer.processor;

import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

import org.hibernate.Session;
import org.hibernate.Transaction;

import seers.appcore.threads.processor.ThreadException;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.cvsanalyzer.git.CommitBean;
import seers.irda.dao.GenericDao;
import seers.irda.dao.impl.RevisionDao;
import seers.irda.entity.Revision;
import seers.irda.entity.SoftwareSystem;

/**
 * From the CVS data, it stores the revisions, files and change sets in the DB
 * 
 * @author ojcch
 *
 */
class PaginatedRevisionProcessor implements ThreadProcessor {

	private String name;
	List<CommitBean> commits;
	private SoftwareSystem system;
	private List<Revision> revisions;

	public PaginatedRevisionProcessor(int fromIndex, int toIndex, Vector<CommitBean> commits, SoftwareSystem system) {
		if (toIndex >= commits.size()) {
			toIndex = commits.size();
		}
		this.commits = commits.subList(fromIndex, toIndex);
		this.system = system;

		name = PaginatedRevisionProcessor.class.getSimpleName() + "-" + fromIndex + "-" + toIndex;
	}

	@Override
	public void processJob() throws ThreadException {

		Session session = GenericDao.openSession();

		try {

			Transaction tx = null;
			revisions = new ArrayList<>();
			try {
				tx = session.beginTransaction();

				RevisionDao revDao = new RevisionDao(session);

				// process every commit
				for (CommitBean commit : commits) {

					Revision revision = revDao.getRevision(commit.getCommitId(), system);
					boolean persist = false;
					if (revision == null) {
						revision = new Revision();
						persist = true;
					}

					revision.setSoftwareSystem(system);
					revision.setCommitId(commit.getCommitId());
					revision.setMessage(commit.getCommitMessage());
					revision.setAuthor(commit.getAuthorEmail());
					revision.setDate(commit.getDate());

					if (persist) {
						revDao.persist(revision);
					} else {
						revDao.update(revision);
					}
					revisions.add(revision);

				}

				tx.commit();
			} catch (Exception e) {
				if (tx != null) {
					tx.rollback();
				}
				throw e;
			}

		} finally {
			session.close();
		}
	}

	@Override
	public String getName() {
		return name;
	}

	public List<Revision> getRevisions() {
		return revisions;
	}

}
