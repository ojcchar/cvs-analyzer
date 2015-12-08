package seers.cvsanalyzer.processor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import org.apache.commons.io.FilenameUtils;
import org.hibernate.Session;
import org.hibernate.Transaction;

import seers.appcore.threads.processor.ThreadException;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.cvsanalyzer.git.CommitBean;
import seers.irda.dao.GenericDao;
import seers.irda.dao.impl.ChangeSetDao;
import seers.irda.dao.impl.CodeFileDao;
import seers.irda.dao.impl.RevisionDao;
import seers.irda.entity.ChangeSet;
import seers.irda.entity.ChangeSetId;
import seers.irda.entity.CodeFile;
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
			List<Revision> revisions = new ArrayList<>();
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

			// add the code files of each revision
			for (int i = 0; i < commits.size(); i++) {
				CommitBean commit = commits.get(i);
				Revision revision = revisions.get(i);
				addCodeFiles(session, commit, revision);
			}

		} finally {
			session.close();
		}
	}

	private void addCodeFiles(Session session, CommitBean commit, Revision revision) {

		// set of files to save
		Set<String> allFiles = new HashSet<>(commit.getAddedFiles());
		allFiles.addAll(commit.getModifiedFiles());
		allFiles.addAll(commit.getDeletedFiles());

		// save the code files
		Map<String, Integer> fileMap = saveCodeFiles(session, allFiles);

		// save the change sets, added, modified and deleted
		saveChangeSets(session, revision, commit.getAddedFiles(), "A", fileMap);
		saveChangeSets(session, revision, commit.getModifiedFiles(), "M", fileMap);
		saveChangeSets(session, revision, commit.getDeletedFiles(), "D", fileMap);
	}

	private Map<String, Integer> saveCodeFiles(Session session, Set<String> allFiles) {

		Transaction tx = null;
		try {
			tx = session.beginTransaction();

			Map<String, Integer> fileMap = new HashMap<>();
			CodeFileDao dao = new CodeFileDao(session);

			// every file is saved
			for (String path : allFiles) {

				CodeFile codeFile = dao.getCodeFile(path, system);

				if (codeFile == null) {
					codeFile = new CodeFile();
					codeFile.setFilePath(path);
					codeFile.setSoftwareSystem(system);
					codeFile.setType(getType(path));

					dao.persist(codeFile);
				}

				fileMap.put(path, codeFile.getId());
			}

			tx.commit();

			return fileMap;
		} catch (Exception e) {
			if (tx != null) {
				tx.rollback();
			}
			throw e;
		}
	}

	private void saveChangeSets(Session session, Revision revision, Vector<String> files, String changeType,
			Map<String, Integer> fileMap) {

		Transaction tx = null;
		try {
			tx = session.beginTransaction();
			ChangeSetDao csDao = new ChangeSetDao(session);

			// every change set is saved
			for (String path : files) {

				// -------------------------------

				Integer fileId = fileMap.get(path);
				ChangeSetId id = new ChangeSetId(fileId, revision.getId(), changeType);
				ChangeSet cs = csDao.getChangeSet(id);

				if (cs == null) {
					cs = new ChangeSet();
					cs.setId(id);
					csDao.persist(cs);
				}

			}

			tx.commit();
		} catch (Exception e) {
			if (tx != null) {
				tx.rollback();
			}
			throw e;
		}
	}

	private String getType(String path) {
		String ext = FilenameUtils.getExtension(path).toUpperCase().trim();
		if (ext.isEmpty()) {
			return "OTHER";
		}
		return ext;
	}

	@Override
	public String getName() {
		return name;
	}

}
