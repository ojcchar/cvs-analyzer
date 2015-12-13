package seers.cvsanalyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import seers.appcore.threads.CommandLatchRunnable;
import seers.appcore.threads.ThreadCommandExecutor;
import seers.cvsanalyzer.processor.BugRevisionProcessor;
import seers.irda.dao.GenericDao;

public class MainBugRevision {
	public static void main(String[] args) throws Exception {

		String[] projectNames = args[0].split(",");
		String destinationFolder = args[1];

		try {

			List<BugRevisionProcessor> procs = new ArrayList<>();
			for (int i = 0; i < projectNames.length; i++) {

				String projectName = projectNames[i];

				BugRevisionProcessor proc = new BugRevisionProcessor(projectName, destinationFolder);
				procs.add(proc);

			}

			ThreadCommandExecutor executor = new ThreadCommandExecutor();
			executor.setCorePoolSize(3);
			try {
				// run the threads
				CountDownLatch cntDwnLatch = new CountDownLatch(procs.size());
				for (BugRevisionProcessor proc : procs) {
					executor.executeCommRunnable(new CommandLatchRunnable(proc, cntDwnLatch));
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
