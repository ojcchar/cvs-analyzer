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
 *
 */
public class App {

	public static void main(String[] args) throws Exception {

		String[] repositoryAddresses = args[0].split(",");
		String[] projectNames = args[1].split(",");
		String destinationFolder = args[2];

		try {

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
					executor.exeucuteCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));
				}
				cntDwnLatch.await();
			} finally {
				executor.shutdown();
			}

		} finally {
			GenericDao.close();
		}
	}

}
