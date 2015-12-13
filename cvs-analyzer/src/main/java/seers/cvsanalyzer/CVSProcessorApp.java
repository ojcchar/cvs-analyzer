package seers.cvsanalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import seers.appcore.threads.CommandLatchRunnable;
import seers.appcore.threads.ThreadCommandExecutor;
import seers.appcore.threads.processor.ThreadProcessor;
import seers.cvsanalyzer.processor.RevisionProcessor;
import seers.irda.dao.GenericDao;

/**
 * Process the Control Version information of a system
 */
public class CVSProcessorApp {

	public static void main(String[] args) throws Exception {

		// urls of the repositories (git)
		String[] repositoryAddresses = args[0].split(",");
		// project names
		String[] projectNames = args[1].split(",");
		// folder where the projects are gonna be cloned
		String destinationFolder = args[2];

		try {

			// threads creation
			List<ThreadProcessor> procs = new ArrayList<>();
			for (int i = 0; i < repositoryAddresses.length; i++) {

				String repositoryAddress = repositoryAddresses[i];
				String projectName = projectNames[i];

				RevisionProcessor proc = new RevisionProcessor(repositoryAddress, projectName, destinationFolder);
				procs.add(proc);

			}

			ThreadCommandExecutor executor = new ThreadCommandExecutor();
			executor.setCorePoolSize(4);
			try {

				// run the threads
				CountDownLatch cntDwnLatch = new CountDownLatch(procs.size());
				for (ThreadProcessor proc : procs) {
					executor.executeCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));
				}

				// wait for the thread to finish
				cntDwnLatch.await();
			} finally {
				executor.shutdown();
			}

		} finally {
			GenericDao.close();
		}
	}

}
