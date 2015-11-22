package seers.cvsanalyzer.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;
import java.util.concurrent.CountDownLatch;

import org.hibernate.Session;
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
import seers.irda.dao.impl.SoftwareSystemDao;
import seers.irda.entity.SoftwareSystem;

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
			paginateCommits(commits);

			LOGGER.info("Done " + projectName);

		} catch (Exception e) {
			ThreadException e2 = new ThreadException(e.getMessage());
			ExceptionUtils.addStackTrace(e, e2);
			throw e2;
		}
	}

	private void paginateCommits(Vector<CommitBean> commits) throws InterruptedException {
		ThreadCommandExecutor executor = new ThreadCommandExecutor();
		try {
			executor.setCorePoolSize(3);

			List<ThreadProcessor> procs = new ArrayList<>();

			int num = commits.size();
			int pageSize = 50;
			for (int offset = 0; offset < num; offset += pageSize) {
				procs.add(new PaginatedRevisionProcessor(offset, offset + pageSize, commits, system));
			}

			// run the threads
			CountDownLatch cntDwnLatch = new CountDownLatch(procs.size());
			for (ThreadProcessor proc : procs) {
				executor.exeucuteCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));

			}
			cntDwnLatch.await();

		} finally {
			executor.shutdown();
		}
	}

	private SoftwareSystem getSystem() {
		Session session = GenericDao.openSession();
		try {
			SoftwareSystemDao sysDao = new SoftwareSystemDao(session);
			SoftwareSystem system = sysDao.getSystem(projectName);

			if (system == null) {
				system = new SoftwareSystem(projectName);
				sysDao.persist(system);
			}
			return system;
		} finally {
			session.close();
		}
	}

	@Override
	public String getName() {
		return name;
	}

}
