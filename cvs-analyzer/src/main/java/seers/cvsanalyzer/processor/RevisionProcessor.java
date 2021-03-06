package seers.cvsanalyzer.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;

import org.apache.commons.io.FilenameUtils;
import org.hibernate.Session;
import org.hibernate.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import seers.appcore.threads.CommandLatchRunnable;
import seers.appcore.threads.ThreadCommandExecutor;
import seers.appcore.threads.processor.ThreadException;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.appcore.utils.ExceptionUtils;
import seers.cvsanalyzer.git.CommitBean;
import seers.cvsanalyzer.git.GitUtilities2;
import seers.irda.dao.GenericDao;
import seers.irda.dao.impl.ChangeSetDao;
import seers.irda.dao.impl.CodeFileDao;
import seers.irda.dao.impl.SoftwareSystemDao;
import seers.irda.entity.ChangeSet;
import seers.irda.entity.ChangeSetId;
import seers.irda.entity.CodeFile;
import seers.irda.entity.Revision;
import seers.irda.entity.SoftwareSystem;

/**
 * Clones a project, reads the history and paginate the commits to be stored in
 * the DB
 * 
 * @author ojcch
 *
 */
public class RevisionProcessor implements ThreadProcessor {

	private Logger LOGGER;

	private String repositoryAddress, projectName, destinationFolder;
	private SoftwareSystem system;

	private String name;

	public RevisionProcessor(String repositoryAddress, String projectName, String destinationFolder) {
		super();
		this.repositoryAddress = repositoryAddress;
		this.projectName = projectName;
		this.destinationFolder = destinationFolder;

		name = RevisionProcessor.class.getSimpleName() + " - " + projectName;
		LOGGER = LoggerFactory.getLogger(name);
		Thread.currentThread().setName(name);
	}

	@Override
	public void processJob() throws ThreadException {

		try {
			String projectFolder = destinationFolder + File.separator + projectName;
			// String logFilePath = projectFolder + File.separator + projectName
			// + ".log";

			LOGGER.info("Cloning " + projectName);
			GitUtilities2.cloneGitRepository(repositoryAddress, projectFolder);

			LOGGER.info("Reading history " + projectName);
			Vector<CommitBean> commits = GitUtilities2.readCommits(projectFolder, null);

			system = getSystem();

			LOGGER.info("Storing info " + projectName);
			// paginateCommits(commits);
			processCommits(commits);

			LOGGER.info("Done " + projectName);

		} catch (Exception e) {
			ThreadException e2 = new ThreadException(e.getMessage());
			ExceptionUtils.addStackTrace(e, e2);
			throw e2;
		}
	}

	/**
	 * From the CVS data, it stores the revisions, files and change sets in the
	 * DB
	 * 
	 * @param commits
	 * @throws InterruptedException
	 */
	private void processCommits(Vector<CommitBean> commits) throws InterruptedException {

		List<Revision> revisions = paginateCommits(commits);
		LOGGER.debug("Commits stored [" + projectName + "]");

		int numCommits = 0;

		// add the code files of each revision
		for (int i = 0; i < commits.size(); i++) {
			numCommits++;

			CommitBean commit = commits.get(i);
			Revision revision = revisions.get(i);
			addCodeFiles(commit, revision);

			if (numCommits % 100 == 0) {
				LOGGER.debug("[" + projectName + "] " + numCommits + "/" + commits.size());
			}
		}
		LOGGER.debug("[" + projectName + "] " + numCommits + "/" + commits.size());
	}

	private void addCodeFiles(CommitBean commit, Revision revision) {

		// set of files to save
		Set<String> allFiles = new HashSet<>(commit.getAddedFiles());
		allFiles.addAll(commit.getModifiedFiles());
		allFiles.addAll(commit.getDeletedFiles());

		// save the code files
		Map<String, Integer> fileMap = saveCodeFiles(allFiles);

		// save the change sets, added, modified and deleted
		saveChangeSets(revision, commit.getAddedFiles(), "A", fileMap);
		saveChangeSets(revision, commit.getModifiedFiles(), "M", fileMap);
		saveChangeSets(revision, commit.getDeletedFiles(), "D", fileMap);
	}

	private Map<String, Integer> saveCodeFiles(Set<String> allFiles) {

		Session session = GenericDao.openSession();
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
		} finally {
			session.close();
		}
	}

	private void saveChangeSets(Revision revision, Vector<String> files, String changeType,
			Map<String, Integer> fileMap) {

		Session session = GenericDao.openSession();
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
		} finally {
			session.close();
		}
	}

	private String getType(String path) {
		String ext = FilenameUtils.getExtension(path).toUpperCase().trim();
		if (ext.isEmpty()) {
			return "OTHER";
		}
		return ext;
	}

	private List<Revision> paginateCommits(Vector<CommitBean> commits) throws InterruptedException {
		ThreadCommandExecutor executor = new ThreadCommandExecutor();
		executor.setCorePoolSize(5);
		try {

			// create the threads
			List<PaginatedRevisionProcessor> procs = new ArrayList<>();
			int num = commits.size();
			int pageSize = 50;
			for (int offset = 0; offset < num; offset += pageSize) {
				procs.add(new PaginatedRevisionProcessor(offset, offset + pageSize, commits, system));
			}

			// run the threads
			CountDownLatch cntDwnLatch = new CountDownLatch(procs.size());
			for (ThreadProcessor proc : procs) {
				executor.executeCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));
			}
			cntDwnLatch.await();

			List<Revision> revisions = new ArrayList<>();
			// list of revisions
			for (PaginatedRevisionProcessor proc : procs) {
				revisions.addAll(proc.getRevisions());
			}

			return revisions;

		} finally {
			executor.shutdown();
		}
	}

	private SoftwareSystem getSystem() {
		Session session = GenericDao.openSession();
		try {
			Transaction tx = null;
			try {
				tx = session.beginTransaction();
				SoftwareSystemDao sysDao = new SoftwareSystemDao(session);
				SoftwareSystem system = sysDao.getSystem(projectName);

				if (system == null) {
					system = new SoftwareSystem(projectName);
					sysDao.persist(system);
				}
				tx.commit();
				return system;
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

}
