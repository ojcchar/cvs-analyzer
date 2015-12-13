package seers.cvsanalyzer.processor;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.eclipse.jgit.lib.Repository;
import org.hibernate.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import seers.appcore.threads.CommandLatchRunnable;
import seers.appcore.threads.ThreadCommandExecutor;
import seers.appcore.threads.processor.ThreadException;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.appcore.utils.ExceptionUtils;
import seers.cvsanalyzer.git.GitUtilities2;
import seers.irda.dao.GenericDao;
import seers.irda.dao.impl.IssueDao;
import seers.irda.dao.impl.SoftwareSystemDao;
import seers.irda.entity.SoftwareSystem;

public class BugRevisionProcessor implements ThreadProcessor {

	private String projectName, destinationFolder;
	private SoftwareSystem system;
	private String name;
	private Logger LOGGER;

	public BugRevisionProcessor(String projectName, String destinationFolder) {
		super();
		this.projectName = projectName;
		this.destinationFolder = destinationFolder;
		name = RevisionProcessor.class.getSimpleName() + " - " + projectName;
		LOGGER = LoggerFactory.getLogger(name);
	}

	@Override
	public void processJob() throws ThreadException {

		setSystem();

		LOGGER.info("Processing system: " + projectName);

		// get number of issues
		Long num = null;
		Session session = GenericDao.openSession();
		try {
			IssueDao iDao = new IssueDao(session);
			num = iDao.getNumIssues(system);
		} finally {
			session.close();
		}

		String projectFolder = destinationFolder + File.separator + projectName;

		ThreadCommandExecutor executor = new ThreadCommandExecutor();
		try {
			Repository repository = GitUtilities2.builRepository(projectFolder);
			executor.setCorePoolSize(3);

			// paginate
			int pageSize = 50;
			List<ThreadProcessor> procs = new ArrayList<>();
			for (int offset = 0; offset < num; offset += pageSize) {
				procs.add(new PaginatedBugRevisionProcessor(pageSize, offset, repository, system));
			}

			// run the threads
			CountDownLatch cntDwnLatch = new CountDownLatch(procs.size());
			for (ThreadProcessor proc : procs) {
				executor.executeCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));

			}

			// wait until they finish
			cntDwnLatch.await();
		} catch (Exception e) {
			ThreadException e2 = new ThreadException(e.getMessage());
			ExceptionUtils.addStackTrace(e, e2);
			throw e2;
		} finally {
			executor.shutdown();
		}
	}

	private void setSystem() throws ThreadException {
		Session session = GenericDao.openSession();
		try {
			SoftwareSystemDao sysDao = new SoftwareSystemDao(session);
			system = sysDao.getSystem(projectName);

			if (system == null) {
				throw new ThreadException("System " + projectName + " does not exist!");
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
