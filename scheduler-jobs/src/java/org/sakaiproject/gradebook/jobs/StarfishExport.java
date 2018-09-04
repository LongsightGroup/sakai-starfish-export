package org.sakaiproject.gradebook.jobs;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.beanutils.ConversionException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.sakaiproject.authz.api.AuthzGroupService;
import org.sakaiproject.authz.api.SecurityService;
import org.sakaiproject.component.api.ServerConfigurationService;
import org.sakaiproject.coursemanagement.api.AcademicSession;
import org.sakaiproject.coursemanagement.api.CourseManagementService;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.event.api.UsageSessionService;
import org.sakaiproject.exception.IdUnusedException;
import org.sakaiproject.gradebook.model.CSVHelper;
import org.sakaiproject.gradebook.model.StarfishAssessment;
import org.sakaiproject.gradebook.model.StarfishScore;
import org.sakaiproject.gradebook.model.StudentGrades;
import org.sakaiproject.service.gradebook.shared.Assignment;
import org.sakaiproject.service.gradebook.shared.CommentDefinition;
import org.sakaiproject.service.gradebook.shared.GradeDefinition;
import org.sakaiproject.service.gradebook.shared.GradebookNotFoundException;
import org.sakaiproject.service.gradebook.shared.GradebookService;
import org.sakaiproject.site.api.Site;
import org.sakaiproject.site.api.SiteService;
import org.sakaiproject.site.api.SiteService.SelectionType;
import org.sakaiproject.site.api.SiteService.SortType;
import org.sakaiproject.tool.api.Session;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.tool.gradebook.Gradebook;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;

import com.opencsv.CSVWriter;
import com.opencsv.bean.ColumnPositionMappingStrategy;
import com.opencsv.bean.StatefulBeanToCsv;
import com.opencsv.bean.StatefulBeanToCsvBuilder;
import com.opencsv.exceptions.CsvDataTypeMismatchException;
import com.opencsv.exceptions.CsvRequiredFieldEmptyException;


/**
 * Job to export gradebook information to CSV for all students in all sites (optionally filtered by term)
 */
@Slf4j
public class StarfishExport implements Job {

	private final String JOB_NAME = "StarfishExport";
	private final static SimpleDateFormat dateFormatter = new SimpleDateFormat("yyyy-MM-dd");
	private final static SimpleDateFormat tsFormatter = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

	// do all of the work
	// this has been combined into one method. It's a lot of code but it reduces additional lookups and duplication of code, refactor if time allows
	public void execute(JobExecutionContext jobExecutionContext) throws JobExecutionException {
		
		log.info(JOB_NAME + " started.");

		//get admin session
		establishSession(JOB_NAME);

		//get all sites that match the criteria
		String[] termEids = serverConfigurationService.getStrings("gradebook.export.term");
		if (termEids == null || termEids.length < 1) {
			termEids = getCurrentTerms();
		}
		
		String fileSep = StringUtils.endsWith(getOutputPath(), File.separator) ? "" : File.separator;
		Path assessmentFile = Paths.get(getOutputPath() + fileSep + "assessments.txt");
		Path scoreFile = Paths.get(getOutputPath() + fileSep + "scores.txt");
	
		//delete existing file so we know the data is current
		if(deleteFile(assessmentFile)) {
			log.debug("New file: " + assessmentFile);
		}
		if(deleteFile(scoreFile)) {
			log.debug("New file: " + assessmentFile);
		}

		ColumnPositionMappingStrategy<StarfishAssessment> assessmentMappingStrategy = new StarfishAssessmentMappingStrategy<>();
		assessmentMappingStrategy.setType(StarfishAssessment.class);
		assessmentMappingStrategy.setColumnMapping(StarfishAssessment.HEADER);

		ColumnPositionMappingStrategy<StarfishScore> scoreMappingStrategy = new StarfishScoreMappingStrategy<>();
		scoreMappingStrategy.setType(StarfishScore.class);
		scoreMappingStrategy.setColumnMapping(StarfishScore.HEADER);

		try (
				BufferedWriter assessmentWriter = Files.newBufferedWriter(assessmentFile, StandardCharsets.UTF_8);
				BufferedWriter scoreWriter = Files.newBufferedWriter(scoreFile, StandardCharsets.UTF_8);
			) {

			StatefulBeanToCsv<StarfishAssessment> assessmentBeanToCsv = new StatefulBeanToCsvBuilder<StarfishAssessment>(assessmentWriter)
				.withMappingStrategy(assessmentMappingStrategy)
				.build();

			StatefulBeanToCsv<StarfishScore> scoreBeanToCsv = new StatefulBeanToCsvBuilder<StarfishScore>(scoreWriter)
					.withMappingStrategy(scoreMappingStrategy)
					.build();

			List<StarfishAssessment> saList = new ArrayList<>();
			List<StarfishScore> scList = new ArrayList<>();
	
			// Loop through all terms provided in sakai.properties
			for (String termEid : termEids) {
	
				List<Site> sites = getSites(termEid);
				log.info("Sites to process for term " + termEid + ": " + sites.size());
	
				for (Site s : sites) {
					String siteId = s.getId();
					String courseSectionIntegrationId = siteId;
					
					// TODO: maybe the proper course_section_integration_id is a site property or an enrollment set?
	
					//get the grades for each site
					List<StudentGrades> grades = new ArrayList<StudentGrades>();
					log.debug("Processing site: " + siteId + " - " + s.getTitle());
	
					//get users in site, skip if none
					List<User> users = getValidUsersInSite(siteId);
					Collections.sort(users, new LastNameComparator());
					if(users == null || users.isEmpty()) {
						log.info("No users in site: " + siteId + ", skipping.");
						continue;
					}
	
					//get gradebook for this site, skip if none
					Gradebook gradebook = null;
					List<Assignment> assignments = new ArrayList<Assignment>();
	
					try {
						gradebook = (Gradebook)gradebookService.getGradebook(siteId);
	
						//get list of assignments in gradebook, skip if none
						assignments = gradebookService.getAssignments(gradebook.getUid());
						if(assignments == null || assignments.isEmpty()) {
							log.info("No assignments for site: " + siteId + ", skipping.");
							continue;
						}
						log.debug("Assignments size: " + assignments.size());
						
						for (Assignment a : assignments) {
							String gbIntegrationId = courseSectionIntegrationId + "-" + a.getId();
							String description = a.getExternalAppName() != null ? "From " + a.getExternalAppName() : "";
							String dueDate = a.getDueDate() != null ? dateFormatter.format(a.getDueDate()) : "";
							int isCounted = a.isCounted() ? 1 : 0;
							StarfishAssessment sa = new StarfishAssessment(gbIntegrationId, courseSectionIntegrationId, a.getName(), description, dueDate, a.getPoints().toString(), isCounted, 0, 0);
							log.debug("StarfishAssessment: {}", sa.toString());
							saList.add(sa);
	
							// for each user, get the assignment results for each assignment
							for (User u : users) {
								StudentGrades g = new StudentGrades(u.getId(), u.getEid());
								log.debug("Member: " + u.getId() + " - " + u.getEid());
	
								//String points = gradebookService.getAssignmentScoreString(gradebook.getUid(), a.getId(), u.getId());
								GradeDefinition gd = gradebookService.getGradeDefinitionForStudentForItem(gradebook.getUid(), a.getId(), u.getId());
								String gradedTimestamp = gd.getDateRecorded() != null ? tsFormatter.format(gd.getDateRecorded()) : "";
								scList.add(new StarfishScore(gbIntegrationId, courseSectionIntegrationId, u.getEid(), gd.getGrade(), "", gradedTimestamp));
								//g.addGrade(a.getId(), points);
								//log.debug("Points: " + points);
							}
						}
	
						//get course grades. This uses entered grades preferentially
						// Map<String, String> courseGrades = gradebookService.getImportCourseGrade(gradebook.getUid());
						saList.add(new StarfishAssessment(courseSectionIntegrationId + "-CG", courseSectionIntegrationId, "Course Grade", "Calculated Course Grade", "", "100", 0, 1, 1));
						//add the course grade. Note the map has eids.
						// g.addGrade(COURSE_GRADE_ASSIGNMENT_ID, courseGrades.get(u.getEid()));
						// log.debug("Course Grade: " + courseGrades.get(u.getEid()));
						// grades.add(g);
					} catch (GradebookNotFoundException gbe) {
						log.info("No gradebook for site: " + siteId + ", skipping.");
						continue;
					} catch (Exception e) {
						log.error("Problem while processing gbExport for site: " + siteId, e);
						continue;
					}
	
					//now write the grades
					if(!grades.isEmpty()) {			
	
						CSVHelper csv = new CSVHelper();
	
						//set the CSV header from the assignment titles and add additional fields
						List<String> header = new ArrayList<String>();
						header.add("Student ID");
						header.add("Student Name");
	
						//add assignment name and then the points possible for the assignment
						//then another column for the comments
						for(Assignment a: assignments) {
							header.add(a.getName() + " [" + a.getPoints() + "]");
							header.add("Comments");
						}
	
						//add these too
						header.add("Total Points Earned [Points Possible]");
						header.add("Course Grade");
	
						// Make sure all row sizes are consistent
						int headerSize = header.size();
	
						csv.setHeader(header.toArray(new String[headerSize]));
	
						//create a formatted list of data using the grade records info and user info, using the order of the assignment list
						//this puts it in the order we need for the CSV
						for(StudentGrades sg: grades) {
	
							List<String> row = new ArrayList<String>(headerSize);
	
							//add name details
							row.add(sg.getUserEid());
							row.add(sg.getDisplayName());		
	
							//add grades
							Map<Long,String> g = sg.getGrades();
							for(Assignment a: assignments) {
								row.add(g.get(a.getId()));
	
								//get comment for each assignment
								CommentDefinition commentDefinition = gradebookService.getAssignmentScoreComment(gradebook.getUid(), a.getId(), sg.getUserId());
								String comment = null;
								if(commentDefinition != null) {
									comment = commentDefinition.getCommentText();
								}
								row.add(comment);
							}
	
							//add total points earned and possible
							// row.add(g.get(TOTAL_POINTS_EARNED) + " [" + g.get(TOTAL_POINTS_POSSIBLE) + "]");
	
							//add course grade
							// row.add(g.get(COURSE_GRADE_ASSIGNMENT_ID));
	
							// Make sure row is same size as header
							if (row.size() != headerSize) {
								log.error("Row not same size as header: " + row.size () + " vs header size of " + headerSize);
							}
	
							log.debug("Row: " + row);
	
							csv.addRow(row.toArray(new String[row.size()]));
						}
	
						//add a row to show the grade mapping (sorted via the value) (2 columns)
						Map<String,Double> baseMap = gradebook.getSelectedGradeMapping().getGradeMap();
						ValueComparator gradeMappingsComparator = new ValueComparator(baseMap);
						TreeMap<String,Double> sortedGradeMappings = new TreeMap<String,Double>(gradeMappingsComparator);
						sortedGradeMappings.putAll(baseMap);
	
						List<String> mappings = new ArrayList<String>();
						for(String key: sortedGradeMappings.keySet()) {
							mappings.add(key + "=" + baseMap.get(key));
						}
	
						// Informational rows. Need to fill out the rows for CSV consistency
						List<String> spacerRow = new ArrayList<String>();
						List<String> siteIdRow = new ArrayList<String>();
						List<String> siteTitleRow = new ArrayList<String>();
						List<String> mappingRow = new ArrayList<String>();
	
						siteIdRow.add("Site ID");
						siteIdRow.add(s.getId());
						siteTitleRow.add("Site Title");
						siteTitleRow.add(s.getTitle());
						mappingRow.add("Mappings");
						mappingRow.add(StringUtils.join(mappings, ','));
	
						for (int i = 0; i < headerSize; i++) {
							if (spacerRow.size() < headerSize) spacerRow.add("");
							if (siteIdRow.size() < headerSize) siteIdRow.add("");
							if (siteTitleRow.size() < headerSize) siteTitleRow.add("");
							if (mappingRow.size() < headerSize) mappingRow.add("");
						}
	
						csv.addRow(spacerRow.toArray(new String[spacerRow.size()]));
						csv.addRow(siteIdRow.toArray(new String[siteIdRow.size()]));
						csv.addRow(siteTitleRow.toArray(new String[siteTitleRow.size()]));
						csv.addRow(mappingRow.toArray(new String[mappingRow.size()]));
	
						//write it all out
						// assessmentWriter.writeNext(csv.getHeader());
						// assessmentWriter.writeAll(csv.getRows());
						// assessmentWriter.close();
	
						log.info("Successfully wrote CSV to: " + assessmentFile);
					}
				}
			}
			
			// Write the entire list of objects out to CSV
			assessmentBeanToCsv.write(saList);
			scoreBeanToCsv.write(scList);
		}
		catch (GradebookNotFoundException e) {
			log.warn("Gradebook not found", e);
		} catch (IOException e) {
			log.error("Could not start writer", e);
		} catch (CsvDataTypeMismatchException e) {
			log.error("Csv mismatch", e);
		} catch (CsvRequiredFieldEmptyException e) {
			log.error("Missing required field for CSV", e);
		}
		
		
		log.info(JOB_NAME + " ended.");
	}
	
	
	/**
	 * Start a session for the admin user and the given jobName
	 */
	private void establishSession(String jobName) {
		
		//set the user information into the current session
	    Session sakaiSession = sessionManager.getCurrentSession();
	    sakaiSession.setUserId("admin");
	    sakaiSession.setUserEid("admin");

	    //establish the user's session
	    usageSessionService.startSession("admin", "127.0.0.1", "starfish-export");
	
	    //update the user's externally provided realm definitions
	    authzGroupService.refreshUser("admin");

	    //post the login event
	    eventTrackingService.post(eventTrackingService.newEvent(UsageSessionService.EVENT_LOGIN, null, true));
	}
	
	
	/**
	 * Get configurable output path. Defaults to /tmp
	 * @return
	 */
	private String getOutputPath() {
		return serverConfigurationService.getString("starfish.export.path", FileUtils.getTempDirectoryPath());
	}
	
	/**
	 * Get all sites that match the criteria, filter out special sites and my workspace sites
	 * @return
	 */
	private List<Site> getSites(String termEid) {

		//setup property criteria
		//this could be extended to dynamically fill the map with properties and values from sakai.props
		Map<String, String> propertyCriteria = new HashMap<String,String>();
		propertyCriteria.put("term_eid", termEid);

		List<Site> sites = new ArrayList<Site>();
			
		List<Site> allSites = siteService.getSites(SelectionType.ANY, null, null, propertyCriteria, SortType.ID_ASC, null);		
		
		for(Site s: allSites) {
			//filter my workspace
			if(siteService.isUserSite(s.getId())){
				continue;
			}
			
			//filter special sites
			if(siteService.isSpecialSite(s.getId())){
				continue;
			}
			
			log.debug("Site: " + s.getId());
			
			//otherwise add it
			sites.add(s);
		}
		
		return sites;
	}
	
	
	/**
	 * Get the users of a site that have the relevant permission
	 * @param siteId
	 * @return list or null if site is bad
	 */
	private List<User> getValidUsersInSite(String siteId) {
		
		try {
			
			Set<String> userIds = siteService.getSite(siteId).getUsersIsAllowed("gradebook.viewOwnGrades");			
			return userDirectoryService.getUsers(userIds);

		} catch (IdUnusedException e) {
			return null;
		}
		
	}
	
	/**
	 * Helper to delete a file. Will only delete files, not directories.
	 * @param assessmentFile	path to file to delete.
	 * @return
	 */
	private boolean deleteFile(Path p) {
		try {
			File f = p.toFile();
			
			//if doesn't exist, return true since we don't need to delete it
			if(!f.exists()) {
				return true;
			}
			
			//check it is a file and delete it
			if(f.isFile()) {
				return f.delete();
			}
			return false;
		} catch (Exception e) {
			return false;
		}
		
	}
	
	/**
	 * Get the most recent active term
	 * @return
	 */
	private String[] getCurrentTerms() {
		Set<String> termSet = new HashSet<>();
		
		List<AcademicSession> sessions = courseManagementService.getCurrentAcademicSessions();
		
		log.debug("terms: " + sessions.size());

		if(sessions.isEmpty()) {
			return null;
		}
				
		for(AcademicSession as: sessions) {
			termSet.add(as.getEid());
			log.debug("term: " + as.getEid());
		}
		
		return termSet.toArray(new String[termSet.size()]);

	}
	
	@Setter
	private SessionManager sessionManager;
	
	@Setter
	private UsageSessionService usageSessionService;
	
	@Setter
	private AuthzGroupService authzGroupService;
	
	@Setter
	private EventTrackingService eventTrackingService;
	
	@Setter
	private ServerConfigurationService serverConfigurationService;
	
	@Setter
	private SiteService siteService;
	
	@Setter
	private UserDirectoryService userDirectoryService;
	
	@Setter
	private GradebookService gradebookService;
	
	@Setter
	private CourseManagementService courseManagementService;
	
	@Setter
	private SecurityService securityService;
	
}

class StarfishAssessmentMappingStrategy<T> extends ColumnPositionMappingStrategy<T> {

    @Override
    public String[] generateHeader() {
        return StarfishAssessment.HEADER;
    }
}

class StarfishScoreMappingStrategy<T> extends ColumnPositionMappingStrategy<T> {

    @Override
    public String[] generateHeader() {
        return StarfishScore.HEADER;
    }
}

/**
 * Comparator class for sorting a list of users by last name
 */
class LastNameComparator implements Comparator<User> {
	
    @Override
    public int compare(User u1, User u2) {
    	return u1.getLastName().compareTo(u2.getLastName());
	}
   
}

/**
 * Comparator class for sorting a grade map by its value
 */
class ValueComparator implements Comparator<String> {

    Map<String, Double> base;
    public ValueComparator(Map<String, Double> base) {
        this.base = base;
    }

    public int compare(String a, String b) {
        if (base.get(a) >= base.get(b)) {
            return -1;
        } else {
            return 1;
        }
    }
}
